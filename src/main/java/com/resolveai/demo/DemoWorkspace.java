package com.resolveai.demo;

import com.resolveai.iam.domain.AppUser;
import com.resolveai.iam.domain.Role;
import com.resolveai.iam.domain.Tenant;
import com.resolveai.iam.repository.AppUserRepository;
import com.resolveai.iam.repository.TenantRepository;
import com.resolveai.iam.security.ResolvePrincipal;
import com.resolveai.platform.tenant.TenantScope;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The demo tenant, and a principal for each role inside it.
 *
 * <p>The demo acts <b>as its seeded users</b> - a customer files the storm's tickets, an
 * agent requests the showcase drafts - through the same services the API calls, so every
 * permission and visibility rule applies to the demo exactly as it does to a person. The
 * principals are built from real rows; nothing here can mint a role a user does not have.
 */
@Component
@ConditionalOnProperty(name = "resolveai.demo.enabled", havingValue = "true")
public class DemoWorkspace {

    private final DemoSettings settings;
    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final TenantScope tenantScope;

    public DemoWorkspace(DemoSettings settings, TenantRepository tenants,
                         AppUserRepository users, TenantScope tenantScope) {
        this.settings = settings;
        this.tenants = tenants;
        this.users = users;
        this.tenantScope = tenantScope;
    }

    public Optional<Tenant> tenant() {
        return tenants.findBySlugAndActiveTrue(settings.tenantSlug());
    }

    public boolean isDemoTenant(Long tenantId) {
        return tenant().map(t -> t.getId().equals(tenantId)).orElse(false);
    }

    /** The first active user with {@code role}, by id - the same person every time. */
    public Optional<ResolvePrincipal> principalFor(Tenant tenant, Role role) {
        return usersWithRole(tenant, role).stream().findFirst()
                .map(u -> principalOf(tenant, u));
    }

    public List<ResolvePrincipal> customers(Tenant tenant) {
        return usersWithRole(tenant, Role.CUSTOMER).stream()
                .map(u -> principalOf(tenant, u))
                .toList();
    }

    private List<AppUser> usersWithRole(Tenant tenant, Role role) {
        return tenantScope.inTenantReadOnly(tenant.getId(), () -> users.findByRole(role).stream()
                .filter(AppUser::isActive)
                .sorted(Comparator.comparing(AppUser::getId))
                .toList());
    }

    private static ResolvePrincipal principalOf(Tenant tenant, AppUser user) {
        return new ResolvePrincipal(user.getId(), tenant.getId(), tenant.getSlug(),
                user.getRole(), "demo");
    }
}
