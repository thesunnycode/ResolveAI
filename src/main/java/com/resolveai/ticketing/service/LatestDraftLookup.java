package com.resolveai.ticketing.service;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The newest draft for a ticket, so the agent view can show a draft that already exists
 * instead of making every visit start from an empty AI rail.
 *
 * <p>Native SQL for the same reason as {@link IncidentLinkLookup}: ticketing must not depend
 * on the drafting module's types, and a single id is all the detail response needs. It
 * carries its own {@code tenant_id} predicate because native queries bypass the Hibernate
 * tenant filter.
 */
@Component
public class LatestDraftLookup {

    private final JdbcTemplate jdbc;

    public LatestDraftLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long latestFor(Long tenantId, Long ticketId) {
        List<Long> ids = jdbc.queryForList("""
                SELECT id FROM draft
                 WHERE ticket_id = ? AND tenant_id = ?
                 ORDER BY id DESC
                 LIMIT 1
                """, Long.class, ticketId, tenantId);
        return ids.isEmpty() ? null : ids.get(0);
    }
}
