package com.resolveai.platform.ai;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads, caches and enforces a tenant's AI policy.
 *
 * <h2>Fail closed</h2>
 *
 * <p>A tenant with no policy row is treated as <b>AI disabled</b>, not as
 * "permissive defaults". This is the single most important line in the class, and it is
 * the opposite of what convenience argues for: a missing row is most likely a tenant
 * provisioned by a path that forgot to create one, and the cost of guessing wrong in the
 * two directions is not symmetrical. Guess "disabled" and somebody files a bug that
 * triage is not running. Guess "allowed" and a customer's messages have already been
 * sent to a third party they never agreed to — which is not a bug, it is a breach, and
 * it cannot be undone by fixing the code.
 *
 * <h2>Cached in Redis, five minutes, evicted on write</h2>
 *
 * <p>Read before <i>every</i> LLM call, so a database round trip per call is real load
 * for a row that changes perhaps monthly. Five minutes bounds how long a stale permissive
 * policy can survive; the eviction on update means the normal path — an admin turning
 * external models off — takes effect immediately rather than in five minutes.
 *
 * <p><b>A cache miss must never mean "allowed".</b> If Redis is down, this reads the
 * database; if the database read fails, the decision is {@link Decision#disabled}. The
 * failure mode of the cache layer is slower, never more permissive.
 */
@Service
public class AiPolicyService {

    private static final Logger log = LoggerFactory.getLogger(AiPolicyService.class);

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final String KEY_PREFIX = "policy:";

    private final TenantAiPolicyRepository policies;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public AiPolicyService(TenantAiPolicyRepository policies, StringRedisTemplate redis,
                           ObjectMapper objectMapper) {
        this.policies = policies;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * What this tenant currently permits.
     *
     * @param externalModelAllowed whether anything may leave the building at all
     * @param allowedProviders     an allow-list; empty means "any configured provider",
     *                             which is different from "none"
     * @param budgetRemainingMicros
     *                             what is left this month. Negative is possible and is
     *                             not an error — see {@code BudgetGuard} for the race
     *                             that allows a bounded overshoot, and why it is accepted
     */
    public record Decision(
            Long tenantId,
            boolean externalModelAllowed,
            List<String> allowedProviders,
            boolean piiRedactionRequired,
            long budgetRemainingMicros) {

        /** The answer when nothing is known. Redaction on, budget zero, nothing allowed. */
        public static Decision disabled(Long tenantId) {
            return new Decision(tenantId, false, List.of(), true, 0L);
        }

        public boolean allowsProvider(String provider) {
            return externalModelAllowed
                   && (allowedProviders.isEmpty() || allowedProviders.contains(provider));
        }

        public boolean hasBudgetFor(long estimatedMicros) {
            return budgetRemainingMicros >= estimatedMicros;
        }
    }

    /** The decision, from cache when possible. */
    @Transactional(readOnly = true)
    public Decision check(Long tenantId) {
        Decision cached = fromCache(tenantId);
        if (cached != null) {
            return cached;
        }
        Decision decision = policies.findById(tenantId)
                .map(AiPolicyService::toDecision)
                .orElseGet(() -> {
                    // Loud, because the only way to notice a tenant provisioned without a
                    // policy is that their AI features quietly do nothing.
                    log.warn("Tenant {} has no AI policy row; treating AI as disabled", tenantId);
                    return Decision.disabled(tenantId);
                });
        toCache(decision);
        return decision;
    }

    @Transactional(readOnly = true)
    public Optional<TenantAiPolicy> find(Long tenantId) {
        return policies.findById(tenantId);
    }

    /** Creates the row if it does not exist. Used by the seeder and by tenant creation. */
    @Transactional
    public TenantAiPolicy ensureExists(Long tenantId) {
        return policies.findById(tenantId).orElseGet(() -> {
            TenantAiPolicy created = policies.save(new TenantAiPolicy(tenantId));
            evict(tenantId);
            return created;
        });
    }

    @Transactional
    public TenantAiPolicy update(Long tenantId, boolean externalModelAllowed,
                                 List<String> allowedProviders, boolean piiRedactionRequired,
                                 long monthlyBudgetMicros, int retentionDays) {
        TenantAiPolicy policy = ensureExists(tenantId);
        policy.setExternalModelAllowed(externalModelAllowed);
        policy.setAllowedProviders(allowedProviders);
        policy.setPiiRedactionRequired(piiRedactionRequired);
        policy.setMonthlyBudgetMicros(monthlyBudgetMicros);
        policy.setRetentionDays(retentionDays);
        policies.saveAndFlush(policy);

        // Evicted rather than rewritten: an admin who has just switched external models
        // off wants that to be true now, not in up to five minutes, and re-reading is
        // cheaper to reason about than keeping two writes in step.
        evict(tenantId);
        log.info("AI policy updated for tenant {}: external={} redaction={} budget={}µ",
                tenantId, externalModelAllowed, piiRedactionRequired, monthlyBudgetMicros);
        return policy;
    }

    /** Called by anything that changes spend, so the next check sees the new remainder. */
    public void evict(Long tenantId) {
        try {
            redis.delete(KEY_PREFIX + tenantId);
        } catch (RuntimeException e) {
            // A failed eviction means a stale entry for up to the TTL, not a wrong write.
            // Worth a line in the log and not worth failing the update over.
            log.warn("Could not evict AI policy cache for tenant {}", tenantId, e);
        }
    }

    private static Decision toDecision(TenantAiPolicy policy) {
        return new Decision(
                policy.getTenantId(),
                policy.isExternalModelAllowed(),
                policy.getAllowedProviders(),
                policy.isPiiRedactionRequired(),
                policy.getMonthlyBudgetMicros() - policy.getCurrentMonthSpendMicros());
    }

    private Decision fromCache(Long tenantId) {
        try {
            String json = redis.opsForValue().get(KEY_PREFIX + tenantId);
            return json == null ? null : objectMapper.readValue(json, Decision.class);
        } catch (RuntimeException e) {
            // Redis down, or an entry written by an older shape of this record. Either
            // way the database is authoritative; the cache is an optimisation and is
            // never allowed to be the reason a call is permitted.
            log.debug("AI policy cache read failed for tenant {}; falling back to the database",
                    tenantId, e);
            return null;
        }
    }

    private void toCache(Decision decision) {
        try {
            redis.opsForValue().set(KEY_PREFIX + decision.tenantId(),
                    objectMapper.writeValueAsString(decision), TTL);
        } catch (RuntimeException e) {
            log.debug("AI policy cache write failed for tenant {}", decision.tenantId(), e);
        }
    }
}
