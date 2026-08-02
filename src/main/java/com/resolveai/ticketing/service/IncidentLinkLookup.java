package com.resolveai.ticketing.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The live incident a ticket belongs to, read at table level.
 *
 * <p>Plain SQL for the same reason {@code TicketQueryRepository}'s incident filter is: the
 * {@code incidents} package already depends on {@code ticketing}, and importing its
 * entities here would close the cycle. Tenant-scoped explicitly because native SQL gets no
 * {@code @TenantId} predicate.
 *
 * <p>Until this existed the ticket DTOs carried a hard-coded {@code null}, so the agent's
 * incident banner and the customer's "we're aware of an issue" hint could never appear.
 */
@Component
public class IncidentLinkLookup {

    /** What the ticket views need: enough to show a banner and link to the incident. */
    public record Link(Long id, String reference, String status) {
        public Map<String, Object> asMap() {
            return Map.of("id", id, "reference", reference, "status", status);
        }
    }

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public IncidentLinkLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    /**
     * @param includeProposed agents see a proposed incident too - it is exactly what they
     *                        should know while deciding whether to confirm it. Customers
     *                        only ever hear about one a person has confirmed.
     */
    public Link liveFor(Long tenantId, Long ticketId, boolean includeProposed) {
        List<Link> rows = jdbc.query("""
                SELECT i.id, i.reference, i.status
                  FROM incident_ticket it
                  JOIN incident i ON i.id = it.incident_id
                 WHERE it.ticket_id = ? AND it.detached_at IS NULL AND i.tenant_id = ?
                   AND i.status IN ('CONFIRMED', 'MITIGATED', ?)
                 ORDER BY i.id DESC
                 LIMIT 1
                """,
                (rs, n) -> new Link(rs.getLong("id"), rs.getString("reference"), rs.getString("status")),
                ticketId, tenantId, includeProposed ? "PROPOSED" : "CONFIRMED");
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * {@link #liveFor} for a whole list page in one statement: {@code DISTINCT ON} keeps the
     * same newest-incident-wins rule the {@code ORDER BY ... LIMIT 1} applies per ticket.
     * Tickets with no live incident are absent from the map.
     */
    public Map<Long, Link> liveForAll(Long tenantId, Collection<Long> ticketIds,
                                      boolean includeProposed) {
        if (ticketIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Link> out = new HashMap<>();
        named.query("""
                SELECT DISTINCT ON (it.ticket_id) it.ticket_id, i.id, i.reference, i.status
                  FROM incident_ticket it
                  JOIN incident i ON i.id = it.incident_id
                 WHERE it.ticket_id IN (:ids) AND it.detached_at IS NULL AND i.tenant_id = :tenantId
                   AND i.status IN ('CONFIRMED', 'MITIGATED', :extra)
                 ORDER BY it.ticket_id, i.id DESC
                """,
                new MapSqlParameterSource().addValue("ids", ticketIds)
                        .addValue("tenantId", tenantId)
                        .addValue("extra", includeProposed ? "PROPOSED" : "CONFIRMED"),
                rs -> {
                    out.put(rs.getLong("ticket_id"), new Link(rs.getLong("id"),
                            rs.getString("reference"), rs.getString("status")));
                });
        return out;
    }
}
