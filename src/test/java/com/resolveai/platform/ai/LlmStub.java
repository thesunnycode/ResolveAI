package com.resolveai.platform.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.resolveai.ticketing.domain.Category;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A language model that does exactly what the test says, and costs nothing.
 *
 * <h2>Why this exists before any test needs it</h2>
 *
 * <p>Retrofitting a stub means re-recording every fixture, and until it exists the suite
 * is slow (a real call is seconds), flaky (providers rate-limit and occasionally answer
 * differently), and <b>billed on every CI run</b>. It also makes the failure paths
 * untestable in practice: nobody can reliably make a real provider return malformed JSON
 * or a 503 on demand, so those branches would go unexercised — and they are the branches
 * that matter.
 *
 * <p>The fluent API is deliberate. {@code LlmStub.returnsSignals(PAYMENT).slow(2s)} reads
 * as the scenario being tested, where raw WireMock JSON reads as configuration and hides
 * what the test is about.
 */
public final class LlmStub {

    /**
     * Matched loosely on purpose. Spring AI 2 talks through the official OpenAI Java
     * SDK, which composes the path from the configured base URL — so whether the request
     * arrives as {@code /v1/chat/completions} or {@code /chat/completions} depends on
     * client internals. A stub that pins the exact path fails with a 404 that looks like
     * a broken provider rather than a mismatched fixture.
     */
    private static final String CHAT_PATH = ".*/chat/completions";
    private static final String EMBEDDINGS_PATH = ".*/embeddings";

    private static WireMockServer server;

    private LlmStub() {
    }

    /** Starts the shared server, once per JVM, on a random port. */
    public static synchronized WireMockServer start() {
        if (server == null) {
            server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
            server.start();
        }
        return server;
    }

    public static synchronized int port() {
        return start().port();
    }

    public static String baseUrl() {
        return "http://localhost:" + port();
    }

    /** Clears every stub between tests, so one test's scenario cannot leak into the next. */
    public static void reset() {
        start().resetAll();
    }

    // ── Scenarios ───────────────────────────────────────────────────────────

    /** A well-formed classification with the given category and sensible defaults. */
    public static void returnsSignals(Category category) {
        returnsSignals(category, "SINGLE_USER", "MEDIUM", false, false,
                category == Category.PAYMENT || category == Category.BILLING, 0.91);
    }

    public static void returnsSignals(Category category, String impact, String urgency,
                                      boolean serviceDown, boolean dataLoss,
                                      boolean paymentAffected, double confidence) {
        String signals = """
                {"category":"%s","reportedImpact":"%s","serviceDownClaimed":%s,\
                "dataLossClaimed":%s,"paymentAffected":%s,"linguisticUrgency":"%s",\
                "extractedEntities":{"orderRef":"«ORDER_REF_1»"},"confidence":%s}"""
                .formatted(category, impact, serviceDown, dataLoss, paymentAffected,
                        urgency, confidence);
        stubChat(200, chatBody(signals), Duration.ZERO);
    }

    /** Content that is not JSON at all — the case the repair attempt exists for. */
    public static void returnsMalformed() {
        stubChat(200, chatBody("I think this is probably a payment issue, quite urgent!"),
                Duration.ZERO);
    }

    /**
     * Valid JSON naming a category that does not exist.
     *
     * <p>The interesting negative case: the syntax is perfect, so a lenient parser
     * happily produces a {@code TriageSignals} with a null category and the policy
     * downstream computes a confident priority from it. It must fail instead.
     */
    public static void returnsUnknownEnum() {
        stubChat(200, chatBody("""
                {"category":"REFUNDS","reportedImpact":"SINGLE_USER",\
                "serviceDownClaimed":false,"dataLossClaimed":false,"paymentAffected":true,\
                "linguisticUrgency":"HIGH","extractedEntities":{},"confidence":0.8}"""),
                Duration.ZERO);
    }

    /** Malformed first, then correct — exercises the repair path end to end. */
    public static void returnsMalformedThenValid(Category category) {
        String scenario = "repair";
        start().stubFor(post(urlPathMatching(CHAT_PATH))
                .inScenario(scenario)
                .whenScenarioStateIs("Started")
                .willSetStateTo("repaired")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(chatBody("not json at all"))));
        start().stubFor(post(urlPathMatching(CHAT_PATH))
                .inScenario(scenario)
                .whenScenarioStateIs("repaired")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(chatBody("""
                                {"category":"%s","reportedImpact":"SINGLE_USER",\
                                "serviceDownClaimed":false,"dataLossClaimed":false,\
                                "paymentAffected":true,"linguisticUrgency":"MEDIUM",\
                                "extractedEntities":{},"confidence":0.77}"""
                                .formatted(category)))));
    }

    public static void fails(int status) {
        stubChat(status, """
                {"error":{"message":"stubbed failure","type":"server_error"}}""", Duration.ZERO);
    }

    public static void slow(Duration delay) {
        returnsSignalsAfter(Category.PAYMENT, delay);
    }

    public static void returnsSignalsAfter(Category category, Duration delay) {
        String signals = """
                {"category":"%s","reportedImpact":"SINGLE_USER","serviceDownClaimed":false,\
                "dataLossClaimed":false,"paymentAffected":true,"linguisticUrgency":"LOW",\
                "extractedEntities":{},"confidence":0.9}""".formatted(category);
        stubChat(200, chatBody(signals), delay);
    }

    /** A 768-dimension embedding of zeros with a single one, enough to be a real vector. */
    public static void returnsEmbedding() {
        String values = java.util.stream.IntStream.range(0, 768)
                .mapToObj(i -> i == 0 ? "0.5" : "0.001")
                .collect(Collectors.joining(","));
        start().stubFor(post(urlPathMatching(EMBEDDINGS_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"object":"list","data":[{"object":"embedding","index":0,\
                                "embedding":[%s]}],"model":"text-embedding-3-small",\
                                "usage":{"prompt_tokens":12,"total_tokens":12}}"""
                                .formatted(values))));
    }

    // ── Drafting (Phase 7) ─────────────────────────────────────────────────
    //
    // draft@1 and entailment@1 share the same /chat/completions endpoint as triage's
    // classification calls, so these stubs are distinguished by matching on a marker
    // string unique to each prompt's own template rather than by path — see
    // ClaimGenerator and EntailmentVerifier for where each marker comes from.

    /** RETRIEVED PASSAGES: only appears in draft@1's rendered prompt. */
    private static final String DRAFT_MARKER = "RETRIEVED PASSAGES";

    /** SPAN:\n only appears in entailment@1's rendered prompt. */
    private static final String ENTAILMENT_MARKER = "SPAN:";

    /**
     * @param claimsJson a JSON array of {@code {"text":..., "citationIds":[...]}}
     *                   objects — exactly the shape {@code ClaimGenerator} expects back
     */
    public static void returnsDraftClaims(String claimsJson, String tone,
                                          List<String> unresolvedAspects) {
        String unresolvedJson = unresolvedAspects.stream()
                .map(a -> "\"" + a.replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(","));
        String body = "{\"claims\":" + claimsJson + ",\"suggestedTone\":\"" + tone
                + "\",\"unresolvedAspects\":[" + unresolvedJson + "]}";
        start().stubFor(post(urlPathMatching(CHAT_PATH))
                .withRequestBody(containing(DRAFT_MARKER))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(chatBody(body))));
    }

    /** Every entailment call, regardless of claim or span, returns the same verdict. */
    public static void returnsEntailmentVerdict(String verdict) {
        start().stubFor(post(urlPathMatching(CHAT_PATH))
                .withRequestBody(containing(ENTAILMENT_MARKER))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(chatBody("{\"verdict\":\"" + verdict + "\"}"))));
    }

    /**
     * Different verdicts depending on a marker substring in the claim text — so a test
     * can make one claim SUPPORTED and another NOT_SUPPORTED in the same draft.
     */
    public static void entailmentVerdictWhenClaimContains(String marker, String verdict) {
        start().stubFor(post(urlPathMatching(CHAT_PATH))
                .withRequestBody(containing(ENTAILMENT_MARKER))
                .withRequestBody(containing(marker))
                .atPriority(1)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(chatBody("{\"verdict\":\"" + verdict + "\"}"))));
    }

    /** How many chat calls have been made — the assertion behind "the cache hit". */
    public static int chatCallCount() {
        return start().findAll(postRequestedFor(urlPathMatching(CHAT_PATH))).size();
    }

    public static int embeddingCallCount() {
        return start().findAll(postRequestedFor(urlPathMatching(EMBEDDINGS_PATH))).size();
    }

    /**
     * Answers differently per eval case, keyed on a marker in the request body.
     *
     * <h2>How a stub can score an eval suite at all</h2>
     *
     * <p>Every classification goes to the same URL with a different body, so a
     * per-request answer needs something in the body to match on. Each eval case
     * carries a {@code Case reference EVALCASE###} sentence for exactly this purpose —
     * stated in {@code EvalCase}'s javadoc rather than hidden, because a fixture
     * mechanism that looks like production data is how a test ends up measuring itself.
     *
     * <p><b>What this measures is the harness, not the prompt.</b> The stub returns
     * whatever this method tells it to, so a "correct" run scores 1.0 by construction.
     * That is still worth having: it proves the suite executes, scores, persists and
     * gates. The accuracy number that means something comes from a run against a live
     * provider, and the gate is what makes that number enforceable.
     *
     * @param answers marker to category, e.g. {@code EVALCASE001 -> PAYMENT}
     */
    public static void classifiesPerCase(Map<String, String> answers) {
        answers.forEach((marker, category) -> {
            String signals = """
                    {"category":"%s","reportedImpact":"SINGLE_USER","serviceDownClaimed":false,                    "dataLossClaimed":false,"paymentAffected":false,"linguisticUrgency":"LOW",                    "extractedEntities":{},"confidence":0.9}""".formatted(category);
            start().stubFor(post(urlPathMatching(CHAT_PATH))
                    .withRequestBody(containing(marker))
                    .willReturn(aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(chatBody(signals))));
        });
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    private static void stubChat(int status, String body, Duration delay) {
        var response = aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
        if (!delay.isZero()) {
            response = response.withFixedDelay((int) delay.toMillis());
        }
        start().stubFor(post(urlPathMatching(CHAT_PATH)).willReturn(response));
    }

    /**
     * An OpenAI chat completion carrying {@code content} as the message.
     *
     * <p>Token counts are present and non-zero on purpose: they drive the cost
     * calculation, and a fixture that reports zero tokens would make every test agree
     * that everything is free.
     */
    private static String chatBody(String content) {
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n");
        return """
                {"id":"chatcmpl-stub","object":"chat.completion","created":1770000000,\
                "model":"gpt-4.1-mini","choices":[{"index":0,"message":{"role":"assistant",\
                "content":"%s"},"finish_reason":"stop"}],\
                "usage":{"prompt_tokens":1187,"completion_tokens":168,"total_tokens":1355}}"""
                .formatted(escaped);
    }

    /** The properties a Spring context needs to talk to this stub instead of a provider. */
    public static Map<String, String> springProperties() {
        return Map.of("spring.ai.openai.base-url", baseUrl(),
                "spring.ai.openai.api-key", "stub-key");
    }
}
