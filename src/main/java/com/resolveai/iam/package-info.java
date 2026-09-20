/**
 * Identity, tenancy, authentication and authorization.
 *
 * <p><b>Owns:</b> {@code tenant}, {@code app_user}, {@code team}, {@code agent_profile}, {@code refresh_token}, JWT issuance and rotation, and the Spring Security filter chain.
 *
 * <p><b>May depend on:</b> {@code platform}, {@code common}.
 *
 * <p>Tenant isolation is enforced here, by Hibernate's {@code @TenantId} and a {@code CurrentTenantIdentifierResolver} fed from the authenticated principal - not by each repository remembering to add {@code AND tenant_id = ?}. A filter that every query inherits cannot be forgotten in one query.
 */
package com.resolveai.iam;
