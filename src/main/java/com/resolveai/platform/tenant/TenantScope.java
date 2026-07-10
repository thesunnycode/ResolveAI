package com.resolveai.platform.tenant;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs transactional work as a given tenant, in the one order that actually works.
 *
 * <h2>The rule this class exists to enforce</h2>
 *
 * <p><b>Hibernate asks the tenant resolver once, when the session opens - not per
 * statement.</b> So this is wrong, and it is wrong in a way that compiles, reads correctly
 * and passes review:
 *
 * <pre>{@code
 * @Transactional                                  // session opens HERE, tenant = NO_TENANT
 * public void seed(Long tenantId) {
 *     TenantContext.runAs(tenantId, () -> {       // too late; the session is already bound
 *         teams.save(new Team(...));              // INSERT ... tenant_id = -1
 *     });
 * }
 * }</pre>
 *
 * <p>It surfaced here as {@code insert or update on table "team" violates foreign key
 * constraint "fk_team_tenant"} with {@code Key (tenant_id)=(-1)} - which is only a legible
 * error because {@link TenantContext#NO_TENANT} is a sentinel that no row can have. Had the
 * resolver returned {@code null} and let Hibernate skip the discriminator, the same mistake
 * would have written rows with no tenant filter at all and reported nothing.
 *
 * <p>The correct order is always: <b>set the tenant, then open the transaction.</b>
 *
 * <pre>{@code
 * tenantScope.inTenant(tenantId, () -> {          // context set first
 *     teams.save(new Team(...));                  // session opens inside it
 *     return null;
 * });
 * }</pre>
 *
 * <p>Every pre-authentication path needs this - login and registration both read
 * tenant-scoped rows before there is a request principal - as do the Phase 6 workers, which
 * have no request thread at all.
 */
@Component
public class TenantScope {

    private final TransactionTemplate transactionTemplate;

    public TenantScope(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    /** Sets the tenant, then opens a transaction inside it. */
    public <T> T inTenant(Long tenantId, Supplier<T> work) {
        return TenantContext.callAs(tenantId,
                () -> transactionTemplate.execute(status -> work.get()));
    }

    public void inTenant(Long tenantId, Runnable work) {
        inTenant(tenantId, () -> {
            work.run();
            return null;
        });
    }

    /**
     * Read-only variant. Worth distinguishing: it lets the driver skip the write-ahead
     * bookkeeping, and it documents at the call site that nothing inside mutates.
     */
    public <T> T inTenantReadOnly(Long tenantId, Supplier<T> work) {
        return TenantContext.callAs(tenantId, () -> {
            var template = new TransactionTemplate(transactionTemplate.getTransactionManager());
            template.setReadOnly(true);
            return template.execute(status -> work.get());
        });
    }
}
