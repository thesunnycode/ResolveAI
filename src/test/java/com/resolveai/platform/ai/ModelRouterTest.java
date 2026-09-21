package com.resolveai.platform.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import com.resolveai.platform.ai.model.EmbeddingService;
import com.resolveai.platform.ai.model.LlmExceptions.BudgetExhaustedException;
import com.resolveai.platform.ai.model.LlmExceptions.LlmParseException;
import com.resolveai.platform.ai.model.LlmExceptions.LlmUnavailableException;
import com.resolveai.platform.ai.model.LlmResult;
import com.resolveai.platform.ai.model.ModelRates;
import com.resolveai.platform.ai.model.ModelRouter;
import com.resolveai.platform.ai.model.TriageSignals;
import com.resolveai.platform.ai.prompt.PromptVersion;
import com.resolveai.platform.ai.prompt.PromptVersionRepository;
import com.resolveai.ticketing.domain.Category;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The model router and everything wrapped around it, against a stubbed provider.
 *
 * <p><b>No test in this file touches the network.</b> That is the point of doing the
 * WireMock work before the tests rather than after: the failure paths — malformed
 * output, a 503, an open breaker — cannot be produced on demand against a real provider,
 * so without a stub they would go untested, and they are the paths that decide whether an
 * AI problem stays an AI problem or becomes an application outage.
 */
class ModelRouterTest extends IntegrationTestBase {

    /**
     * Points Spring AI at the stub.
     *
     * <p>{@code @DynamicPropertySource} rather than a fixed port in
     * {@code application-test.yml}: a fixed port collides with whatever else is running
     * on a developer's machine, and the failure looks like a broken test rather than a
     * busy socket.
     */
    @DynamicPropertySource
    static void stubProvider(DynamicPropertyRegistry registry) {
        LlmStub.start();
        registry.add("spring.ai.openai.base-url", LlmStub::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> "stub-key");
    }

    @Autowired AuthTestSupport auth;
    @Autowired ModelRouter router;
    @Autowired AiPolicyService policies;
    @Autowired EmbeddingService embeddings;
    @Autowired ModelRates rates;
    @Autowired PromptVersionRepository prompts;
    @Autowired CircuitBreakerRegistry breakers;
    @Autowired JdbcTemplate jdbc;

    private Long tenantId;
    private PromptVersion triagePrompt;

    @BeforeEach
    void seed() {
        auth.wipe();
        tenantId = auth.seedTenant("router").tenantId();
        policies.ensureExists(tenantId);
        policies.evict(tenantId);
        triagePrompt = prompts.findByNameAndActiveTrue("triage").orElseThrow();
        LlmStub.reset();
        breakers.circuitBreaker("openai").reset();
    }

    // ── Structured output ───────────────────────────────────────────────────

    @Test
    @DisplayName("A well-formed response populates TriageSignals and records its cost")
    void populatesSignals() {
        LlmStub.returnsSignals(Category.PAYMENT);

        LlmResult<TriageSignals> result =
                router.call(tenantId, triagePrompt, "UPI debited, order still unpaid",
                        TriageSignals.class);

        assertThat(result.value().category()).isEqualTo(Category.PAYMENT);
        assertThat(result.value().paymentAffected()).isTrue();
        assertThat(result.promptLabel()).isEqualTo("triage@1");
        assertThat(result.attempt()).isEqualTo(1);
        // Cost comes from the real token counts in the response, through the rate table,
        // as an integer. 1187 in and 168 out at gpt-4.1-mini's published rate.
        assertThat(result.costMicros())
                .isEqualTo(rates.costMicros("gpt-4.1-mini", 1187, 168))
                .isPositive();
        // And the tenant has been charged for it.
        assertThat(spend()).isEqualTo(result.costMicros());
    }

    @Test
    @DisplayName("The signals carry no priority — the model reports, the policy decides")
    void signalsCarryNoPriority() {
        LlmStub.returnsSignals(Category.AUTH);

        LlmResult<TriageSignals> result = router.call(tenantId, triagePrompt,
                "Locked out since the reset", TriageSignals.class);

        // Guarding the architectural claim in a test rather than only in a comment: if
        // someone adds a priority field to TriageSignals, this fails and they have to
        // read why it is not allowed.
        assertThat(TriageSignals.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("priority", "severity", "urgency")
                .contains("linguisticUrgency", "reportedImpact");
        assertThat(result.value().linguisticUrgency()).isNotNull();
    }

    @Test
    @DisplayName("Malformed output is repaired once, and the attempt is recorded")
    void repairsMalformedOutputOnce() {
        LlmStub.returnsMalformedThenValid(Category.BILLING);

        LlmResult<TriageSignals> result = router.call(tenantId, triagePrompt,
                "Charged for PRO after downgrading", TriageSignals.class);

        assertThat(result.value().category()).isEqualTo(Category.BILLING);
        // Attempt 2: the first response was unusable. Recorded because a tenant whose
        // classifications routinely need repairing has a prompt problem, and nothing
        // else would show it.
        assertThat(result.attempt()).isEqualTo(2);
        assertThat(LlmStub.chatCallCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Output that cannot be repaired fails typed, carrying the raw text")
    void unrepairableOutputThrowsLlmParseException() {
        LlmStub.returnsMalformed();

        assertThatThrownBy(() -> router.call(tenantId, triagePrompt, "Nonsense in",
                TriageSignals.class))
                .isInstanceOf(LlmParseException.class)
                .satisfies(e -> assertThat(((LlmParseException) e).rawOutput()).isNotBlank());

        // Exactly two calls: the original and one repair. A retry loop here would cost
        // real money on every unparseable ticket and arrive at the same answer.
        assertThat(LlmStub.chatCallCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("An unknown enum value fails rather than defaulting")
    void unknownEnumValueIsRejected() {
        LlmStub.returnsUnknownEnum();

        // Syntactically perfect JSON naming a category that does not exist. A lenient
        // parser yields TriageSignals with a null category, the policy computes a
        // confident priority from it, and the ticket is routed to a team nobody chose.
        assertThatThrownBy(() -> router.call(tenantId, triagePrompt, "Refund please",
                TriageSignals.class))
                .isInstanceOf(LlmParseException.class);
    }

    // ── Policy ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A tenant that forbids external models never reaches the network")
    void policyRefusalHappensBeforeTheCall() {
        LlmStub.returnsSignals(Category.PAYMENT);
        policies.update(tenantId, false, List.of(), true, 5_000_000L, 365);

        assertThatThrownBy(() -> router.call(tenantId, triagePrompt, "Anything",
                TriageSignals.class))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("does not permit");

        // The assertion that matters: no request was made. A refusal that happens after
        // the text has been sent is not a refusal.
        assertThat(LlmStub.chatCallCount()).isZero();
    }

    @Test
    @DisplayName("A tenant with no policy row is treated as AI-disabled")
    void missingPolicyFailsClosed() {
        LlmStub.returnsSignals(Category.PAYMENT);
        jdbc.update("DELETE FROM tenant_ai_policy WHERE tenant_id = ?", tenantId);
        policies.evict(tenantId);

        // Fail closed. A missing row most likely means a tenant provisioned by a path
        // that forgot to create one; guessing "allowed" would send their customers'
        // messages to a third party they never agreed to, which is not a bug that can be
        // fixed after the fact.
        assertThatThrownBy(() -> router.call(tenantId, triagePrompt, "Anything",
                TriageSignals.class))
                .isInstanceOf(LlmUnavailableException.class);
        assertThat(LlmStub.chatCallCount()).isZero();
    }

    @Test
    @DisplayName("An allow-list that excludes the provider blocks the call")
    void providerAllowListIsEnforced() {
        LlmStub.returnsSignals(Category.PAYMENT);
        policies.update(tenantId, true, List.of("ollama"), true, 5_000_000L, 365);

        assertThatThrownBy(() -> router.call(tenantId, triagePrompt, "Anything",
                TriageSignals.class))
                .isInstanceOf(LlmUnavailableException.class);
        assertThat(LlmStub.chatCallCount()).isZero();
    }

    // ── Budget ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A tenant at their budget is refused before the call, not after")
    void budgetExhaustionRefusesBeforeSpending() {
        LlmStub.returnsSignals(Category.PAYMENT);
        policies.update(tenantId, true, List.of(), true, 1L, 365);

        assertThatThrownBy(() -> router.call(tenantId, triagePrompt, "A long ticket body "
                .repeat(50), TriageSignals.class))
                .isInstanceOf(BudgetExhaustedException.class);

        assertThat(LlmStub.chatCallCount()).isZero();
        assertThat(spend()).isZero();
    }

    @Test
    @DisplayName("Spend accumulates exactly under ten concurrent calls")
    void spendAccumulatesUnderConcurrency() throws Exception {
        LlmStub.returnsSignals(Category.PAYMENT);
        long perCall = rates.costMicros("gpt-4.1-mini", 1187, 168);
        policies.update(tenantId, true, List.of(), true, perCall * 1000, 365);

        int calls = 10;
        var ready = new java.util.concurrent.CountDownLatch(calls);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(calls)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < calls; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    return router.call(tenantId, triagePrompt, "Concurrent",
                            TriageSignals.class);
                }));
            }
            assertThat(ready.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            release.countDown();
            for (var future : futures) {
                future.get(60, java.util.concurrent.TimeUnit.SECONDS);
            }
        }

        // The *check* is racy by design — two calls can both pass it and overshoot by one
        // call's cost. The *total* is not: an atomic SQL increment means ten calls record
        // exactly ten calls' worth, where a read-modify-write would lose several of them
        // and quietly under-bill.
        assertThat(spend()).isEqualTo(perCall * calls);
    }

    // ── Circuit breaker ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Repeated provider failures open the breaker, and it then refuses locally")
    void breakerOpensAndRefusesWithoutCallingOut() {
        LlmStub.fails(503);
        policies.update(tenantId, true, List.of(), true, 100_000_000L, 365);

        // Five failures is the configured minimum before the window is evaluated.
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> router.call(tenantId, triagePrompt, "Body",
                    TriageSignals.class))
                    .isInstanceOf(LlmUnavailableException.class);
        }
        int callsBeforeOpen = LlmStub.chatCallCount();
        assertThat(breakers.circuitBreaker("openai").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> router.call(tenantId, triagePrompt, "Body",
                TriageSignals.class))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("breaker is open");

        // No further network traffic. This is the whole value of the breaker: without
        // it, every worker keeps burning its retry budget against a provider that is
        // down, and a degraded AI feature becomes an application-wide slowdown.
        assertThat(LlmStub.chatCallCount()).isEqualTo(callsBeforeOpen);
    }

    @Test
    @DisplayName("A parse failure does not count against the breaker")
    void parseFailuresDoNotOpenTheBreaker() {
        LlmStub.returnsMalformed();

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> router.call(tenantId, triagePrompt, "Body",
                    TriageSignals.class))
                    .isInstanceOf(LlmParseException.class);
        }

        // The socket worked perfectly every time; the model's output was the problem.
        // Counting that as a provider failure would open the breaker on a prompt bug and
        // stop calls that would have succeeded.
        assertThat(breakers.circuitBreaker("openai").getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ── Embeddings ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("The second embedding of identical text costs nothing and calls nobody")
    void embeddingsAreCachedOnContentHash() {
        LlmStub.returnsEmbedding();
        String redacted = "Order «ORDER_REF_1» never arrived and «PERSON_1» is waiting";

        float[] first = embeddings.embed(tenantId, redacted);
        long spendAfterFirst = spend();
        float[] second = embeddings.embed(tenantId, redacted);

        assertThat(first).hasSize(768).isEqualTo(second);
        assertThat(LlmStub.embeddingCallCount()).isEqualTo(1);
        // Not charged twice either. The cache is keyed on a hash of the redacted text,
        // so two tickets differing only in a customer's name share one vector — which is
        // exactly what incident correlation needs them to do.
        assertThat(spend()).isEqualTo(spendAfterFirst);
    }

    private long spend() {
        Long spent = jdbc.queryForObject(
                "SELECT current_month_spend_micros FROM tenant_ai_policy WHERE tenant_id = ?",
                Long.class, tenantId);
        return spent == null ? 0L : spent;
    }
}
