package com.resolveai.iam.web.dto;

import com.resolveai.iam.domain.Tenant;

/**
 * The public, unauthenticated view of a tenant: enough to populate a "pick your business"
 * dropdown on the login/register pages, and nothing else - no plan tier, no id, no counts.
 */
public record TenantSummaryResponse(String slug, String name) {

    public static TenantSummaryResponse from(Tenant tenant) {
        return new TenantSummaryResponse(tenant.getSlug(), tenant.getName());
    }
}
