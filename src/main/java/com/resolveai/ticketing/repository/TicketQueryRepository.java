package com.resolveai.ticketing.repository;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.common.pagination.Cursor;
import com.resolveai.iam.domain.Role;
import com.resolveai.iam.security.ResolvePrincipal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The agent queue: the highest-traffic query in the application.
 *
 * <h2>⚠️ This is the first native SQL in the project, and the first place the
 * {@code @TenantId} gap bites</h2>
 *
 * <p>Hibernate's tenant discriminator is applied to HQL and criteria queries. <b>It is not
 * applied to native SQL.</b> Every statement built here therefore carries an explicit
 * {@code AND t.tenant_id = :tenantId}, added first, before any other predicate, and it is
 * not optional or conditional. Phases 6 to 8 add more native queries — the outbox claim,
 * hybrid retrieval — and each one inherits this obligation.
 *
 * <p>{@code CrossTenantAccessTest} is what notices if one of them forgets.
 *
 * <h2>Why native and not a Criteria specification</h2>
 *
 * <p>Two features need it. Full-text search is {@code search_tsv @@ plainto_tsquery(...)},
 * a Postgres operator with no JPA equivalent; and keyset pagination is
 * {@code (created_at, id) < (:c, :i)}, a row-value comparison the Criteria API cannot
 * express and which is the only form the planner will drive {@code idx_ticket_queue} from.
 * Emulating either in HQL produces a query that works and does not use the index.
 *
 * <h2>Search uses {@code plainto_tsquery}, never concatenation</h2>
 *
 * <p>The search term is user input arriving in a query parameter. Bound as a parameter it is
 * data; concatenated into the SQL it is a query, and {@code tsquery} syntax has enough
 * operators to make that interesting.
 */
@Repository
public class TicketQueryRepository {

    /**
     * Sort columns, as a fixed allow-list.
     *
     * <p>{@code sort} arrives from the client and is interpolated into the SQL — a column
     * name cannot be a bind parameter. <b>An allow-list is therefore load-bearing, not
     * defensive:</b> the alternative is string-building an ORDER BY from user input, which
     * is SQL injection with extra steps.
     */
    private static final Map<String, String> SORTABLE = Map.of(
            "created_at", "t.created_at",
            "priority", "t.priority",
            "updated_at", "t.updated_at");

    private final NamedParameterJdbcTemplate jdbc;

    public TicketQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Everything the list endpoint can filter on. */
    public record Filters(
            Cursor cursor,
            int size,
            Set<String> statuses,
            Set<String> priorities,
            Long teamId,
            Long assigneeId,
            boolean unassignedOnly,
            Long incidentId,
            Long requesterId,
            String slaState,
            String q,
            OffsetDateTime createdFrom,
            OffsetDateTime createdTo,
            String sort,
            String order) {
    }

    /**
     * Returns ticket ids in page order, over-fetching by one so the caller can answer
     * {@code hasNext} without a second query.
     *
     * <p><b>Ids, not rows.</b> The rows are then loaded through JPA, which is what keeps the
     * response mapper working with entities and their associations instead of a
     * {@code ResultSet}. The cost is one extra round trip; the benefit is that the
     * projection lives in one place and the N+1 risk is visible rather than hidden in a
     * hand-written SELECT list.
     */
    public List<Long> findPageIds(ResolvePrincipal principal, Long callerTeamId, Filters f) {
        StringBuilder sql = new StringBuilder("SELECT t.id FROM ticket t WHERE ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> where = new ArrayList<>();

        // ── The tenant predicate. First, unconditional, non-negotiable. ──────
        where.add("t.tenant_id = :tenantId");
        params.addValue("tenantId", principal.tenantId());

        appendRoleScope(principal, callerTeamId, where, params);

        if (f.statuses() != null && !f.statuses().isEmpty()) {
            where.add("t.status IN (:statuses)");
            params.addValue("statuses", f.statuses());
        }
        if (f.priorities() != null && !f.priorities().isEmpty()) {
            where.add("t.priority IN (:priorities)");
            params.addValue("priorities", f.priorities());
        }
        if (f.teamId() != null) {
            where.add("t.team_id = :filterTeamId");
            params.addValue("filterTeamId", f.teamId());
        }
        if (f.unassignedOnly()) {
            where.add("t.assignee_id IS NULL");
        } else if (f.assigneeId() != null) {
            where.add("t.assignee_id = :filterAssigneeId");
            params.addValue("filterAssigneeId", f.assigneeId());
        }
        if (f.requesterId() != null) {
            // "This customer's other tickets" on the agent ticket view. Still inside the
            // role scope above, so an agent sees only what their team could see anyway.
            where.add("t.requester_id = :filterRequesterId");
            params.addValue("filterRequesterId", f.requesterId());
        }
        if (f.incidentId() != null) {
            where.add("""
                    EXISTS (SELECT 1 FROM incident_ticket it
                             WHERE it.ticket_id = t.id AND it.incident_id = :incidentId)
                    """);
            params.addValue("incidentId", f.incidentId());
        }
        if (f.slaState() != null) {
            // BREACHED is a stored state. AT_RISK is derived — a running clock whose
            // deadline is inside the next two hours — because there is no at_risk column
            // and a boolean written by a poller would be stale between runs.
            if ("BREACHED".equals(f.slaState())) {
                where.add("""
                        EXISTS (SELECT 1 FROM sla_record s
                                 WHERE s.ticket_id = t.id AND s.state = 'BREACHED')
                        """);
            } else {
                where.add("""
                        EXISTS (SELECT 1 FROM sla_record s
                                 WHERE s.ticket_id = t.id AND s.state = 'RUNNING'
                                   AND s.next_deadline_at <= NOW() + INTERVAL '2 hours')
                        """);
            }
        }
        if (f.q() != null && !f.q().isBlank()) {
            // Full text over subject and body, OR an exact reference ("TKT-1036"). Agents
            // paste references from customer emails; the text index tokenises them away.
            where.add("(t.search_tsv @@ plainto_tsquery('english', :q)"
                    + " OR upper(t.reference) = upper(:qRef))");
            params.addValue("q", f.q());
            params.addValue("qRef", f.q().trim());
        }
        if (f.createdFrom() != null) {
            where.add("t.created_at >= :createdFrom");
            params.addValue("createdFrom", f.createdFrom());
        }
        if (f.createdTo() != null) {
            where.add("t.created_at <= :createdTo");
            params.addValue("createdTo", f.createdTo());
        }

        boolean descending = !"asc".equalsIgnoreCase(f.order());
        String sortColumn = SORTABLE.get(f.sort() == null ? "created_at" : f.sort());
        if (sortColumn == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "sort must be one of " + SORTABLE.keySet().stream().sorted().toList() + ".");
        }

        if (f.cursor() != null) {
            // Row-value comparison. Including the id is what makes the boundary
            // unambiguous when two tickets share a created_at, which happens constantly
            // during a burst. Keyset pagination with a non-unique key silently skips or
            // repeats a row, and the agent never finds out.
            where.add(descending
                    ? "(t.created_at, t.id) < (:cursorCreatedAt, :cursorId)"
                    : "(t.created_at, t.id) > (:cursorCreatedAt, :cursorId)");
            params.addValue("cursorCreatedAt", f.cursor().createdAt());
            params.addValue("cursorId", f.cursor().id());
        }

        sql.append(String.join("\n   AND ", where));
        // The tiebreaker on id must match the cursor's tuple exactly, or the page boundary
        // and the sort order disagree and rows go missing between pages.
        sql.append("\n ORDER BY ").append(sortColumn).append(descending ? " DESC" : " ASC")
           .append(", t.id").append(descending ? " DESC" : " ASC")
           .append("\n LIMIT :limit");
        params.addValue("limit", f.size() + 1);

        return jdbc.queryForList(sql.toString(), params, Long.class);
    }

    /**
     * The role scoping table from doc 05 §3.2, <b>applied server-side and not overridable by
     * any query parameter.</b>
     *
     * <p>An agent with no team sees only their own assignments. That is deliberate rather
     * than a special case: {@code team_id IS NULL} would otherwise match every unassigned
     * ticket in the tenant, so an agent who has not been put in a team yet would see more
     * than one who has.
     */
    private static void appendRoleScope(ResolvePrincipal principal, Long callerTeamId,
                                        List<String> where, MapSqlParameterSource params) {
        switch (principal.role()) {
            case ADMIN -> {
                // Everything in the tenant. The tenant predicate above is the only scope.
            }
            case TEAM_LEAD -> {
                where.add(callerTeamId == null ? "FALSE" : "t.team_id = :callerTeamId");
                params.addValue("callerTeamId", callerTeamId);
            }
            case AGENT -> {
                where.add(callerTeamId == null
                        ? "t.assignee_id = :callerId"
                        : "(t.assignee_id = :callerId OR t.team_id = :callerTeamId)");
                params.addValue("callerId", principal.userId());
                params.addValue("callerTeamId", callerTeamId);
            }
            case CUSTOMER -> {
                where.add("t.requester_id = :callerId");
                params.addValue("callerId", principal.userId());
            }
        }
    }

    /**
     * Message counts for a page of tickets, in one query.
     *
     * <p>{@code LinkedHashMap} so the iteration order matches the page order, which keeps
     * the mapper's output deterministic and the tests readable.
     */
    public Map<Long, Long> messageCounts(List<Long> ticketIds, Long tenantId) {
        return messageCounts(ticketIds, tenantId, false);
    }

    /**
     * @param publicOnly count only {@code PUBLIC} messages - what a customer's list must
     *                   use, or the count itself reveals that internal notes exist
     */
    public Map<Long, Long> messageCounts(List<Long> ticketIds, Long tenantId, boolean publicOnly) {
        if (ticketIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> counts = new LinkedHashMap<>();
        jdbc.query("""
                SELECT ticket_id, count(*) AS n
                  FROM ticket_message
                 WHERE tenant_id = :tenantId AND ticket_id IN (:ids)
                   AND (:publicOnly = FALSE OR visibility = 'PUBLIC')
                 GROUP BY ticket_id
                """,
                new MapSqlParameterSource().addValue("tenantId", tenantId)
                        .addValue("ids", ticketIds).addValue("publicOnly", publicOnly),
                rs -> {
                    counts.put(rs.getLong("ticket_id"), rs.getLong("n"));
                });
        return counts;
    }

    /** The newest PUBLIC message per ticket, and whether support wrote it. One query. */
    public Map<Long, com.resolveai.ticketing.web.dto.TicketSummaryResponse.LastReply> lastPublicReplies(
            List<Long> ticketIds, Long tenantId) {
        if (ticketIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, com.resolveai.ticketing.web.dto.TicketSummaryResponse.LastReply> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT DISTINCT ON (m.ticket_id) m.ticket_id, m.created_at, u.role
                  FROM ticket_message m
                  JOIN app_user u ON u.id = m.author_id
                 WHERE m.tenant_id = :tenantId AND m.ticket_id IN (:ids)
                   AND m.visibility = 'PUBLIC'
                 ORDER BY m.ticket_id, m.created_at DESC, m.id DESC
                """,
                new MapSqlParameterSource().addValue("tenantId", tenantId)
                        .addValue("ids", ticketIds),
                rs -> {
                    out.put(rs.getLong("ticket_id"),
                            new com.resolveai.ticketing.web.dto.TicketSummaryResponse.LastReply(
                                    rs.getObject("created_at", java.time.OffsetDateTime.class),
                                    !"CUSTOMER".equals(rs.getString("role"))));
                });
        return out;
    }
}
