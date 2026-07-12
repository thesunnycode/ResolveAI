package com.resolveai.iam.security;

import com.resolveai.iam.domain.Tenant;
import com.resolveai.iam.repository.AppUserRepository;
import com.resolveai.iam.repository.TenantRepository;
import com.resolveai.platform.tenant.TenantContext;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Loads a user by {@code (tenantSlug, email)}.
 *
 * <p><b>This deliberately does not implement Spring's {@code UserDetailsService}.</b> That
 * interface takes a single {@code String username}, and the usual workaround is to encode a
 * composite like {@code "acme:priya@example.com"} and split it again inside. The composite
 * exists only to satisfy an interface this application does not otherwise use: the JWT
 * filter builds its principal from token claims rather than a lookup, so login is the one
 * caller. A two-argument method that says what it means is better than a string-packing
 * convention that has to be remembered at both ends.
 *
 * <p><b>The lookup runs inside {@code TenantContext.callAs}</b>, because {@code AppUser} is
 * {@code @TenantId}-filtered and login happens before any tenant context exists. Without it
 * the query would run under the {@code NO_TENANT} sentinel and find nobody - which is the
 * safe direction to fail, and exactly why that sentinel exists.
 */
@Service
public class TenantAwareUserDetailsService {

    private final TenantRepository tenants;
    private final AppUserRepository users;

    public TenantAwareUserDetailsService(TenantRepository tenants, AppUserRepository users) {
        this.tenants = tenants;
        this.users = users;
    }

    /**
     * <b>Deliberately not {@code @Transactional}.</b> A transaction opened here would bind
     * its Hibernate session before {@code callAs} sets the tenant, and the user lookup would
     * run under the {@code NO_TENANT} sentinel and find nobody - a login failure with no
     * error, for a correct password. See {@code TenantScope} for the general rule.
     */
    public Optional<ResolveUserDetails> loadUser(String tenantSlug, String email) {
        Optional<Tenant> tenant = tenants.findBySlugAndActiveTrue(tenantSlug);
        if (tenant.isEmpty()) {
            return Optional.empty();
        }
        Tenant t = tenant.get();
        return TenantContext.callAs(t.getId(), () ->
                users.findByEmailIgnoreCase(email.trim().toLowerCase())
                        .map(u -> new ResolveUserDetails(u, t.getSlug())));
    }
}
