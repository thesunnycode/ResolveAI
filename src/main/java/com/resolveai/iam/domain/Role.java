package com.resolveai.iam.domain;

/**
 * The four roles, in ascending order of capability.
 *
 * <p><b>Persisted as a string</b> ({@code @Enumerated(STRING)}), and the database enforces
 * the same four values with {@code ck_user_role}. Ordinal storage would mean that inserting
 * a role into the middle of this enum silently reinterprets every existing row - the single
 * most damaging mapping mistake available in JPA, and one that produces no error at all.
 */
public enum Role {
    CUSTOMER,
    AGENT,
    TEAM_LEAD,
    ADMIN;

    /** True when this role is at least as capable as {@code other}. */
    public boolean atLeast(Role other) {
        return ordinal() >= other.ordinal();
    }

    /** Spring Security's convention: authorities carry the {@code ROLE_} prefix. */
    public String authority() {
        return "ROLE_" + name();
    }
}
