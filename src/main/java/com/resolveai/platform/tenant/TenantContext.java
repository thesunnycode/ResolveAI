package com.resolveai.platform.tenant;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Holds the tenant for the current thread. Hibernate's tenant resolver reads it on every
 * query, so whatever is in here decides what the database is allowed to return.
 *
 * <p><b>The {@code finally} in every method here is the security control.</b> A leaked
 * {@code ThreadLocal} on a pooled Tomcat thread means the <i>next</i> request runs under the
 * previous request's tenant - a cross-tenant data breach caused by a missing {@code finally},
 * with no exception, no log line and no failing test.
 *
 * <p><b>An unset context does not mean "all tenants".</b> {@link ResolveTenantIdentifierResolver}
 * substitutes a sentinel that matches no row, so the failure mode of forgetting to set a
 * tenant is an empty result rather than a full table. That direction is the only acceptable
 * one, and it is the default a hand-rolled filter usually gets wrong.
 */
public final class TenantContext {

    /**
     * Returned by the resolver when nothing is set. No tenant has this id, so every query
     * made without a context returns nothing.
     */
    public static final Long NO_TENANT = -1L;

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Long tenantId) {
        CURRENT.set(tenantId);
    }

    /** The current tenant, or {@code null} when none is set. */
    public static Long get() {
        return CURRENT.get();
    }

    /**
     * The current tenant, or an exception. Used where proceeding without one would be a
     * bug rather than a legitimate unauthenticated path.
     */
    public static Long getRequired() {
        Long id = CURRENT.get();
        if (id == null) {
            throw new IllegalStateException(
                    "No tenant in context. A request path reached tenant-scoped code without "
                    + "authentication, or a worker forgot TenantContext.runAs.");
        }
        return id;
    }

    public static boolean isSet() {
        return CURRENT.get() != null;
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Runs work as a given tenant and restores whatever was there before.
     *
     * <p>Restoring the previous value rather than clearing matters for nesting: a service
     * already running inside one tenant that calls {@code runAs} for another must not come
     * back with no tenant at all, because the code after the call would then silently see
     * empty results.
     *
     * <p>Used by the Phase 6 workers, which have no request thread, and by login and
     * registration, which have to read tenant-scoped rows before the caller is authenticated.
     */
    public static void runAs(Long tenantId, Runnable work) {
        callAs(tenantId, () -> {
            work.run();
            return null;
        });
    }

    public static <T> T callAs(Long tenantId, Supplier<T> work) {
        Long previous = CURRENT.get();
        CURRENT.set(tenantId);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** {@link #callAs} for work that throws checked exceptions. */
    public static <T> T callAsChecked(Long tenantId, Callable<T> work) throws Exception {
        Long previous = CURRENT.get();
        CURRENT.set(tenantId);
        try {
            return work.call();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
