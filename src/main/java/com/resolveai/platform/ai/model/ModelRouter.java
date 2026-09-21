package com.resolveai.platform.ai.model;

import com.resolveai.platform.ai.AiPolicyService;
import com.resolveai.platform.ai.AiSpendRecorder;
import com.resolveai.platform.ai.model.LlmExceptions.BudgetExhaustedException;
import com.resolveai.platform.ai.model.LlmExceptions.LlmParseException;
import com.resolveai.platform.ai.model.LlmExceptions.LlmUnavailableException;
import com.resolveai.platform.ai.prompt.PromptVersion;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The only way anything in this system talks to a language model.
 *
 * <h2>What it is for</h2>
 *
 * <p>Not abstraction for its own sake. Four things have to happen around every call, and
 * every one of them is a thing that is forgotten at some call site if it is left to call
 * sites: <b>is this tenant allowed</b>, <b>can they afford it</b>, <b>is the provider
 * currently healthy</b>, and <b>what did it cost</b>. A service that calls
 * {@code ChatClient} directly has none of them, and the absence is invisible until a
 * customer's data has gone somewhere it should not have, or a bill arrives.
 *
 * <h2>The lean ladder</h2>
 *
 * <p>The full design escalates: cheap model, then a strong one on low confidence, then a
 * fallback provider on 5xx, then local Ollama when external models are forbidden. This
 * implements <b>policy check → one model → typed failure</b>, which is steps 1, 2 and 6.
 * Steps 3 to 5 are a day of work that the demo does not show, and the shape here has the
 * seam for them: {@link #chooseModel} is where the ladder would branch, and everything
 * around it already records which model was used.
 *
 * <h2>Nothing here holds a database connection during the call</h2>
 *
 * <p>The policy read and the spend write are separate, short transactions either side of
 * the network call, which itself runs with none open. This is the rule from
 * {@code WorkerRuntime}, enforced at the one place every model call passes through — see
 * {@link #call} for the ordering.
 */
@Service
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    private static final String PROVIDER_OPENAI = "openai";

    private final ChatCaller chatCaller;
    private final AiPolicyService policies;
    private final AiSpendRecorder spendRecorder;
    private final ModelRates rates;
    private final CircuitBreakerRegistry breakers;
    private final MeterRegistry metrics;

    public ModelRouter(ChatCaller chatCaller, AiPolicyService policies,
                       AiSpendRecorder spendRecorder, ModelRates rates,
                       CircuitBreakerRegistry breakers, MeterRegistry metrics) {
        this.chatCaller = chatCaller;
        this.policies = policies;
        this.spendRecorder = spendRecorder;
        this.rates = rates;
        this.breakers = breakers;
        this.metrics = metrics;
        registerBreakerGauge(PROVIDER_OPENAI);
    }

    /**
     * Runs one prompt and returns a typed result.
     *
     * <p>Order matters and is worth reading as a sequence: refuse on policy, refuse on
     * budget, call through the breaker, record the spend. The two refusals happen
     * <i>before</i> any network traffic, because the entire point of both is that the
     * traffic does not happen.
     *
     * @throws LlmUnavailableException  policy forbids it, or the provider could not be
     *                                  reached
     * @throws BudgetExhaustedException the tenant has spent their month
     * @throws LlmParseException        the model answered unusably, twice
     */
    public <T> LlmResult<T> call(Long tenantId, PromptVersion prompt, String userText,
                                 Class<T> type) {
        AiPolicyService.Decision decision = policies.check(tenantId);

        // 1. Policy. Fails closed: a tenant with no policy row reaches here as "disabled".
        if (!decision.allowsProvider(PROVIDER_OPENAI)) {
            metrics.counter("ai.calls.refused", "reason", "policy").increment();
            throw new LlmUnavailableException(
                    "Tenant " + tenantId + " does not permit calls to " + PROVIDER_OPENAI
                    + ". No text left the building.");
        }

        // 2. Budget, checked against an estimate before the call.
        //
        // THE RACE, NAMED: two calls can both pass this check and jointly overshoot the
        // budget by up to one call's cost. That is accepted. Closing it means serialising
        // every LLM call for a tenant behind a lock, which would turn a parallel worker
        // pool into a queue of one for the sake of a bounded overshoot of a few hundred
        // micros. The *total* stays exact regardless, because the spend is recorded with
        // an atomic SQL increment rather than a read-modify-write — so the budget is
        // enforced late by at most one call, never wrong.
        long estimate = rates.estimateMicros(prompt.getModelId(), estimateTokens(userText));
        if (!decision.hasBudgetFor(estimate)) {
            metrics.counter("ai.calls.refused", "reason", "budget").increment();
            throw new BudgetExhaustedException(tenantId,
                    decision.budgetRemainingMicros() + estimate, estimate);
        }

        // 3. The call itself, through the breaker, with no transaction open.
        CircuitBreaker breaker = breakers.circuitBreaker(PROVIDER_OPENAI);
        ChatCaller.Completion<T> completion;
        try {
            completion = breaker.executeSupplier(
                    () -> chatCaller.complete(prompt, userText, type));
        } catch (CallNotPermittedException e) {
            // The breaker is open: the provider has been failing and this call is being
            // refused without a network round trip. That is the point — an open breaker
            // is what stops every worker burning its full retry budget against a
            // provider that is down, which is how a degraded AI feature becomes an
            // application-wide slowdown.
            metrics.counter("ai.calls.refused", "reason", "breaker_open").increment();
            throw new LlmUnavailableException(
                    "The " + PROVIDER_OPENAI + " circuit breaker is open; not calling out", e);
        } catch (LlmParseException | LlmUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LlmUnavailableException("Call to " + PROVIDER_OPENAI + " failed", e);
        }

        // 4. Record what it actually cost, from the real token counts.
        long cost = rates.costMicros(completion.modelId(), completion.tokensIn(),
                completion.tokensOut());
        spendRecorder.record(tenantId, cost);

        metrics.counter("ai.calls.succeeded", "model", completion.modelId()).increment();
        metrics.counter("ai.cost.micros", "model", completion.modelId()).increment(cost);

        return new LlmResult<>(completion.value(), completion.modelId(), prompt.label(),
                completion.tokensIn(), completion.tokensOut(), cost,
                completion.latencyMs(), completion.attempt());
    }

    /**
     * Which model runs this prompt.
     *
     * <p>Today: the one named on the prompt version. This is the seam the escalation
     * ladder plugs into — a strong model on low confidence, a fallback provider on 5xx,
     * local Ollama when external models are forbidden — and it is a separate method so
     * that adding those is a change here rather than a change in {@link #call}.
     */
    String chooseModel(PromptVersion prompt) {
        return prompt.getModelId();
    }

    /**
     * Tokens, roughly, from characters.
     *
     * <p>Four characters per token is the usual English approximation and is wrong for
     * Hindi transliteration, which this product sees plenty of. It is only used for the
     * pre-call budget estimate, where being approximately right early beats being exactly
     * right after the money is spent — the recorded figure uses the provider's own count.
     */
    private static int estimateTokens(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 4);
    }

    /**
     * Breaker state as a number, for the dashboard.
     *
     * <p>0 closed, 1 half-open, 2 open. Worth graphing next to the triage queue depth:
     * a queue that grows while the breaker is open is a provider outage, and a queue
     * that grows while it is closed is a capacity problem. The two need different
     * responses and look identical without this.
     */
    private void registerBreakerGauge(String provider) {
        metrics.gauge("ai.breaker.state", java.util.List.of(
                        io.micrometer.core.instrument.Tag.of("provider", provider)),
                breakers, registry -> switch (registry.circuitBreaker(provider).getState()) {
                    case CLOSED, DISABLED, METRICS_ONLY -> 0.0;
                    case HALF_OPEN -> 1.0;
                    case OPEN, FORCED_OPEN -> 2.0;
                });
        log.info("AI circuit breaker registered for provider {}", provider);
    }
}
