package com.resolveai.iam.domain;

/**
 * The tenant's plan. Feeds the SLA policy lookup and the PLAN_TIER_BUMP priority rule, so it
 * is a business input rather than a billing label.
 */
public enum PlanTier {
    FREE,
    PRO,
    ENTERPRISE
}
