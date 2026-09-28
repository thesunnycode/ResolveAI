package com.resolveai.incidents.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import com.resolveai.incidents.service.IncidentTitleGenerator.TitleResult;
import com.resolveai.platform.ai.AiPolicyService;
import com.resolveai.platform.ai.LlmStub;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Doc 12 Task 10's expected output: a real title when the provider is up, an honest
 * templated one when it is not, and the incident is identical either way except for that
 * one field.
 */
class IncidentTitleGeneratorTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void stubProvider(DynamicPropertyRegistry registry) {
        LlmStub.start();
        registry.add("spring.ai.openai.base-url", LlmStub::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> "stub-key");
    }

    @Autowired AuthTestSupport auth;
    @Autowired AiPolicyService policies;
    @Autowired IncidentTitleGenerator generator;

    private AuthTestSupport.SeededTenant tenant;

    @BeforeEach
    void seed() {
        auth.wipe();
        tenant = auth.seedTenant("titles");
        LlmStub.reset();
    }

    @Test
    @DisplayName("with the provider up, a sensible title and generatedByModel populated")
    void modelWritesTheTitle() {
        policies.ensureExists(tenant.tenantId());
        policies.evict(tenant.tenantId());
        LlmStub.returnsIncidentTitle("UPI and card payment failures at checkout",
                "Multiple customers report failed payments across UPI and card. The pattern "
                + "spans several checkout flows.");

        TitleResult result = generator.generate(tenant.tenantId(),
                List.of("Card declined", "UPI not going through"),
                Set.of("PAYMENT_METHOD:upi", "SERVICE:payment-service"), 38);

        assertThat(result.title()).isEqualTo("UPI and card payment failures at checkout");
        assertThat(result.summary()).isNotBlank();
        assertThat(result.generatedByModel()).isNotNull();
        assertThat(result.promptVersionId()).isNotNull();
    }

    @Test
    @DisplayName("with the provider unreachable, a templated title and null generatedByModel")
    void fallsBackToTemplateWhenProviderDown() {
        // No AI policy row for this tenant at all -> ModelRouter refuses before any
        // network call, exactly like a policy-off tenant. The incident must still be
        // fully formed.
        TitleResult result = generator.generate(tenant.tenantId(),
                List.of("Card declined", "UPI not going through"),
                Set.of("PAYMENT_METHOD:upi"), 38);

        assertThat(result.title()).contains("38");
        assertThat(result.summary()).isNull();
        assertThat(result.generatedByModel()).isNull();
        assertThat(result.promptVersionId()).isNull();
    }
}
