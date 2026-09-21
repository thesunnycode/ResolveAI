package com.resolveai.iam.security;

import com.resolveai.iam.domain.Role;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Maps a role to the permission strings {@code GET /auth/me} returns.
 *
 * <p><b>This array exists so the frontend can hide controls the user cannot use. The server
 * remains the only authority.</b> Every endpoint is independently protected by
 * {@code @PreAuthorize} on the service method, so a client that forges a permission string
 * gains a visible button and nothing else - the call behind it still returns 403.
 *
 * <p>One switch over the enum, in one class, because this is the only place the
 * role-to-capability mapping is written down and doc 06 consumes it to decide which nav
 * items to render. Spreading it across controllers would guarantee the UI and the server
 * disagree within a month.
 */
@Component
public class PermissionResolver {

    public List<String> permissionsFor(Role role) {
        return switch (role) {
            case CUSTOMER -> List.of(
                    "ticket:create",
                    "ticket:read:own",
                    "ticket:reply:own",
                    "ticket:reopen:own",
                    "attachment:upload",
                    "notification:read:own");

            case AGENT -> List.of(
                    "ticket:create",
                    "ticket:read:team",
                    "ticket:reply",
                    "ticket:note:internal",
                    "ticket:assign:self",
                    "ticket:status",
                    "ticket:resolve",
                    "ticket:retriage",
                    "ticket:priority:override",
                    "sla:pause",
                    "draft:request",
                    "knowledge:read",
                    "incident:read",
                    "attachment:upload",
                    "notification:read:own",
                    "agent:availability:self");

            case TEAM_LEAD -> concat(permissionsFor(Role.AGENT), List.of(
                    "ticket:read:all",
                    "ticket:assign:any",
                    "ticket:reassign",
                    "incident:confirm",
                    "incident:reject",
                    "incident:link",
                    "incident:publish",
                    "incident:resolve"));

            case ADMIN -> concat(permissionsFor(Role.TEAM_LEAD), List.of(
                    "admin:users",
                    "admin:teams",
                    "admin:sla-policy",
                    "admin:calendar",
                    "admin:ai-policy",
                    "admin:usage",
                    "admin:eval",
                    "admin:audit",
                    "admin:outbox",
                    "knowledge:write",
                    "knowledge:reindex"));
        };
    }

    private static List<String> concat(List<String> a, List<String> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).distinct().sorted().toList();
    }
}
