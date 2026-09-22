package com.resolveai.incidents.repository;

import com.resolveai.common.pagination.Cursor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The board's keyset pagination — {@code idx_incident_board (tenant_id, status,
 * detected_at DESC)}. Native for the same reason {@code TicketQueryRepository} is: a
 * row-value comparison on {@code (detected_at, id)} is not expressible through the
 * Criteria API in a form the planner will use the index for.
 *
 * <p>Explicit {@code tenant_id} predicate, because {@code @TenantId} does not reach
 * native SQL — see {@code TicketQueryRepository}'s class comment for the rule this
 * follows.
 */
@Repository
public class IncidentQueryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public IncidentQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Ids in board order, over-fetched by one so {@code hasNext} needs no second query. */
    public List<Long> pageIds(Long tenantId, Set<String> statuses, Cursor cursor, int size) {
        var sql = new StringBuilder("""
                SELECT id FROM incident WHERE tenant_id = :tenantId
                """);
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId);

        if (statuses != null && !statuses.isEmpty()) {
            sql.append(" AND status IN (:statuses)");
            params.addValue("statuses", statuses);
        }
        if (cursor != null) {
            sql.append(" AND (detected_at, id) < (:cursorAt, :cursorId)");
            params.addValue("cursorAt", cursor.createdAt()).addValue("cursorId", cursor.id());
        }
        sql.append(" ORDER BY detected_at DESC, id DESC LIMIT :limit");
        params.addValue("limit", size + 1);

        List<Long> ids = new ArrayList<>();
        jdbc.query(sql.toString(), params, rs -> {
            ids.add(rs.getLong("id"));
        });
        return ids;
    }
}
