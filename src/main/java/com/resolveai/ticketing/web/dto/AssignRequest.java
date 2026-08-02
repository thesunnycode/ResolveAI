package com.resolveai.ticketing.web.dto;

import jakarta.validation.constraints.Pattern;

/**
 * @param assigneeId a numeric user id, the literal {@code "me"}, or omitted (which also
 *                   means self-assign). A string rather than a {@code Long} because doc 05
 *                   specifies the {@code "me"} alias, and binding it as a number would make
 *                   the documented value a 400.
 * @param force      {@code TEAM_LEAD}+ only. Reassigns a ticket that already has an
 *                   assignee, using a statement without the {@code assignee_id IS NULL}
 *                   guard. Kept as an explicit opt-in so that the ordinary path cannot
 *                   accidentally steal work from another agent.
 */
public record AssignRequest(

        @Pattern(regexp = "me|\\d{1,19}", message = "Must be a user id or the literal 'me'")
        String assigneeId,

        Boolean force) {

    public boolean isSelfAssign() {
        return assigneeId == null || "me".equals(assigneeId);
    }

    public boolean forced() {
        return Boolean.TRUE.equals(force);
    }
}
