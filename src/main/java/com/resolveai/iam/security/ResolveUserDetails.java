package com.resolveai.iam.security;

import com.resolveai.iam.domain.AppUser;
import com.resolveai.iam.domain.Role;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * A loaded user, with the tenant information Spring's {@code UserDetails} has no room for.
 */
public class ResolveUserDetails implements UserDetails {

    private final Long userId;
    private final Long tenantId;
    private final String tenantSlug;
    private final String email;
    private final String passwordHash;
    private final String fullName;
    private final Role role;
    private final boolean active;
    private final boolean deleted;

    public ResolveUserDetails(AppUser user, String tenantSlug) {
        this.userId = user.getId();
        this.tenantId = user.getTenantId();
        this.tenantSlug = tenantSlug;
        this.email = user.getEmail();
        this.passwordHash = user.getPasswordHash();
        this.fullName = user.getFullName();
        this.role = user.getRole();
        this.active = user.isActive();
        this.deleted = user.getDeletedAt() != null;
    }

    public Long getUserId() { return userId; }
    public Long getTenantId() { return tenantId; }
    public String getTenantSlug() { return tenantSlug; }
    public String getEmail() { return email; }
    public String getFullName() { return fullName; }
    public Role getRole() { return role; }
    public String getPasswordHash() { return passwordHash; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    /** {@code is_active}. A deactivated account authenticates and is then refused. */
    @Override
    public boolean isEnabled() {
        return active;
    }

    /** {@code deleted_at IS NULL}. In practice the soft-delete restriction means a deleted
     *  user is never loaded at all; this is the second line of defence. */
    @Override
    public boolean isAccountNonLocked() {
        return !deleted;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}
