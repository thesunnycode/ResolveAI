package com.resolveai.platform.tenant;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Tells Hibernate which tenant the current thread is operating as.
 *
 * <p><b>This is the mechanism that makes a forgotten {@code WHERE tenant_id} impossible.</b>
 * With {@code @TenantId} on an entity, Hibernate appends the discriminator to every HQL and
 * criteria query <i>and</i> sets it on insert - so a repository method cannot read another
 * tenant's rows, and a service cannot write a row into the wrong tenant even by assigning
 * the field explicitly.
 *
 * <p><b>Why this rather than {@code @Filter}.</b> A filter has to be enabled on each session
 * by hand. One code path that forgets reopens the entire hole, silently, and nothing fails.
 * The discriminator has no opt-in step to forget.
 *
 * <p><b>The known gap, stated here because it will be asked about:</b> none of this applies
 * to <b>native SQL</b>. Phases 6 to 8 use native queries for the outbox claim
 * ({@code FOR UPDATE SKIP LOCKED}) and for hybrid retrieval, and every one of those must
 * carry an explicit {@code AND tenant_id = :tenantId}. The mechanism covers the common path;
 * {@code CrossTenantAccessTest} is what covers the rest, and neither is sufficient alone.
 */
@Component
public class ResolveTenantIdentifierResolver
        implements CurrentTenantIdentifierResolver<Long>, HibernatePropertiesCustomizer {

    @Override
    public Long resolveCurrentTenantIdentifier() {
        Long current = TenantContext.get();
        // Never null, and never "no filtering". A missing tenant resolves to an id no row
        // has, so an unauthenticated or mis-wired path sees an empty database rather than
        // everyone's.
        return current != null ? current : TenantContext.NO_TENANT;
    }

    /**
     * {@code false} because the tenant is fixed for the lifetime of a request or a worker
     * task; Hibernate does not need to re-validate it against sessions already open, and
     * saying {@code true} makes it throw when a session outlives a context change.
     */
    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }

    /**
     * Registers this resolver with Hibernate. Done in code rather than as a
     * {@code spring.jpa.properties.*} string because that route instantiates the class
     * reflectively, outside the Spring context - which works only as long as the resolver
     * needs no injected collaborators, and stops working silently the first time it does.
     */
    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
    }
}
