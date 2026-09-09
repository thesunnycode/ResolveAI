package com.resolveai.incidents.repository;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Native reads for the correlation sweep. Native because {@code embedding} is unmapped
 * JPA-side (see {@code Ticket}'s class comment) and because a sweep pass wants every
 * candidate ticket in the window in one round trip, not N lazy loads.
 *
 * <p>Every statement carries an explicit {@code tenant_id} predicate — {@code @TenantId}
 * does not reach native SQL, the same rule {@code TicketQueryRepository}'s class comment
 * states, and {@code CrossTenantAccessTest} is what would notice if one of these forgot it.
 */
@Repository
public class CorrelationCandidateRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CorrelationCandidateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Tenants with any ticket activity in the window — the sweep's outer loop. */
    public List<Long> activeTenantIds(OffsetDateTime windowStart) {
        return jdbc.queryForList("""
                SELECT DISTINCT tenant_id FROM ticket
                 WHERE created_at >= :windowStart AND status <> 'CLOSED'
                """, new MapSqlParameterSource("windowStart", windowStart), Long.class);
    }

    public record CandidateRow(Long ticketId, String embeddingLiteral, OffsetDateTime createdAt) {
    }

    /** Open tickets for one tenant in the window that have an embedding to cluster on. */
    public List<CandidateRow> findCandidates(Long tenantId, OffsetDateTime windowStart) {
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("windowStart", windowStart);
        return jdbc.query("""
                SELECT id, embedding::text AS embedding_text, created_at
                  FROM ticket
                 WHERE tenant_id = :tenantId
                   AND created_at >= :windowStart
                   AND status <> 'CLOSED'
                   AND embedding IS NOT NULL
                """, params, (rs, i) -> new CandidateRow(rs.getLong("id"),
                rs.getString("embedding_text"), rs.getObject("created_at", OffsetDateTime.class)));
    }

    /**
     * How many tickets in the window have no embedding yet — triage still running, or
     * failed. Logged at DEBUG by the sweep: a rising count means AI is degraded and
     * correlation is silently weakening, which is worth knowing without being an alert.
     */
    public int countMissingEmbeddings(Long tenantId, OffsetDateTime windowStart) {
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("windowStart", windowStart);
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ticket
                 WHERE tenant_id = :tenantId AND created_at >= :windowStart
                   AND status <> 'CLOSED' AND embedding IS NULL
                """, params, Integer.class);
        return count == null ? 0 : count;
    }
}
