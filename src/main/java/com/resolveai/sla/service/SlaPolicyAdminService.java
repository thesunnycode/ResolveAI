package com.resolveai.sla.service;

import com.resolveai.iam.domain.PlanTier;
import com.resolveai.iam.domain.Tenant;
import com.resolveai.iam.repository.TenantRepository;
import com.resolveai.platform.time.DatabaseClock;
import com.resolveai.sla.domain.SlaPolicy;
import com.resolveai.sla.repository.SlaPolicyRepository;
import com.resolveai.ticketing.domain.Priority;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lets an {@code ADMIN} see and change the SLA targets their own tenant's tickets resolve
 * against - the write path {@link SlaPolicyResolver} has always assumed exists, and that
 * until now only a migration or a manual insert could provide. See the note on
 * {@link com.resolveai.sla.web.SlaPolicyAdminController}: a tenant only ever has one plan
 * tier, so this always reads and writes at that plan tier, not the full matrix.
 */
@Service
public class SlaPolicyAdminService {

    private static final Logger log = LoggerFactory.getLogger(SlaPolicyAdminService.class);

    private final SlaPolicyRepository policies;
    private final TenantRepository tenants;
    private final DatabaseClock clock;

    public SlaPolicyAdminService(SlaPolicyRepository policies, TenantRepository tenants,
                                 DatabaseClock clock) {
        this.policies = policies;
        this.tenants = tenants;
        this.clock = clock;
    }

    /** One row per real priority, {@code configured: false} where the tenant has none yet. */
    @Transactional(readOnly = true)
    public List<SlaPolicyRow> currentForTenant() {
        PlanTier planTier = requireTenantPlanTier();
        return Arrays.stream(Priority.values())
                .filter(Priority::isTriaged)
                .map(priority -> policies.findByPriorityAndPlanTierAndEffectiveToIsNull(priority, planTier)
                        .map(SlaPolicyRow::from)
                        .orElseGet(() -> SlaPolicyRow.unconfigured(priority)))
                .toList();
    }

    @Transactional
    public SlaPolicyRow supersede(Priority priority, int firstResponseMinutes, int resolutionMinutes) {
        PlanTier planTier = requireTenantPlanTier();
        OffsetDateTime now = clock.now();

        Optional<SlaPolicy> live = policies.findByPriorityAndPlanTierAndEffectiveToIsNull(priority, planTier);
        int nextVersion = 1;
        if (live.isPresent()) {
            SlaPolicy current = live.get();
            current.supersedeAt(now);
            policies.saveAndFlush(current);
            nextVersion = parseVersion(current.getVersionLabel()) + 1;
        }

        SlaPolicy created = new SlaPolicy(priority, planTier, firstResponseMinutes,
                resolutionMinutes, now, "v" + nextVersion);
        policies.saveAndFlush(created);

        log.info("SLA policy for {}/{} superseded: {}m first response, {}m resolution ({})",
                priority, planTier, firstResponseMinutes, resolutionMinutes, created.getVersionLabel());
        return SlaPolicyRow.from(created);
    }

    private PlanTier requireTenantPlanTier() {
        Long tenantId = com.resolveai.platform.tenant.TenantContext.getRequired();
        Tenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("Tenant " + tenantId + " not found"));
        return tenant.getPlanTier();
    }

    private static int parseVersion(String label) {
        try {
            return Integer.parseInt(label.replaceFirst("^v", ""));
        } catch (NumberFormatException e) {
            // A policy created outside this service (a migration, a script) may not follow
            // the "vN" convention. Starting the count over from here is harmless: the label
            // is a display/audit string, never a key anything joins on.
            return 0;
        }
    }

    public record SlaPolicyRow(
            Priority priority,
            boolean configured,
            Integer firstResponseMinutes,
            Integer resolutionMinutes,
            List<Integer> escalationRungs,
            String versionLabel,
            OffsetDateTime effectiveFrom) {

        static SlaPolicyRow from(SlaPolicy policy) {
            return new SlaPolicyRow(
                    policy.getPriority(),
                    true,
                    policy.getFirstResponseMinutes(),
                    policy.getResolutionMinutes(),
                    policy.rungs().stream().map(Short::intValue).toList(),
                    policy.getVersionLabel(),
                    policy.getEffectiveFrom());
        }

        static SlaPolicyRow unconfigured(Priority priority) {
            return new SlaPolicyRow(priority, false, null, null, List.of(), null, null);
        }
    }
}
