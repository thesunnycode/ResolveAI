package com.resolveai.iam.web.dto;

import com.resolveai.iam.domain.Role;

/**
 * What the accept-invite page shows before anyone has typed anything: enough to render
 * "You've been invited to join {tenantName} as {role}" without requiring auth, and nothing
 * that would let the token be used to enumerate anything about the tenant.
 */
public record InvitePreviewResponse(String tenantName, String email, Role role) {
}
