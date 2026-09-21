package com.resolveai.ticketing.service;

import com.resolveai.ticketing.web.dto.AnalysisResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Derives what state a ticket's triage is in.
 *
 * <h2>The state is computed, not stored</h2>
 *
 * <p>There is no {@code analysis_status} column, and adding one would be the obvious
 * mistake. The truth is spread across two tables that are each already authoritative
 * about their own half — {@code ai_analysis} knows whether a result exists, the outbox
 * knows whether a job is still trying — and a third column would be a denormalised copy
 * that some failure path forgets to update. The one it would forget is the crash path,
 * which is exactly the case the endpoint exists to report.
 *
 * <pre>
 *   analysis row with status OK        → READY
 *   analysis row with status FAILED    → UNAVAILABLE (the model answered, unusably)
 *   outbox event PENDING or IN_FLIGHT  → PROCESSING
 *   outbox event DEAD                  → UNAVAILABLE (we gave up)
 *   neither                            → PROCESSING, briefly, or UNAVAILABLE
 * </pre>
 *
 * <p>Reading the outbox from a ticketing service is a deliberate, narrow dependency: the
 * platform module owns the queue, and this asks it one read-only question about a job it
 * published itself. The alternative — ticketing keeping its own copy of "is triage still
 * running" — is the denormalised column again, with the same failure mode.
 */
@Service
public class AnalysisReadService {

    /**
     * How often to poll while PROCESSING.
     *
     * <p>Three seconds against a triage that takes two to eight. Polling faster produces
     * load and no information; slower makes a fast triage look slow, which is the thing
     * the whole async design is trying not to be visible as.
     */
    private static final int RETRY_AFTER_SECONDS = 3;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AnalysisReadService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * @param tenantId <b>not redundant, even though the controller has already called
     *                 {@code TicketAccess.loadVisible}.</b> Both queries below are
     *                 native, and {@code @TenantId} does not reach native SQL — so the
     *                 only thing standing between a foreign ticket id and another
     *                 tenant's classification is that every caller remembered to check
     *                 first. That is a property that holds until the first caller that
     *                 does not, and the signals are a summary of somebody's private
     *                 support ticket.
     */
    @Transactional(readOnly = true)
    public AnalysisResponse forTicket(Long tenantId, Long ticketId) {
        // Mapped by hand rather than through queryForList: that helper hands back
        // java.sql.Timestamp for a timestamptz, and the cast to OffsetDateTime then fails
        // at runtime on a line that reads as though it could not. rs.getObject(_, Class)
        // converts in the driver, where the zone information still exists.
        List<AnalysisRow> analyses = jdbc.query("""
                SELECT a.status, a.signals, a.confidence, a.model_id, a.tokens_in,
                       a.tokens_out, a.cost_micros, a.latency_ms, a.attempt, a.created_at,
                       p.name AS prompt_name, p.version AS prompt_version
                  FROM ai_analysis a
                  JOIN prompt_version p ON p.id = a.prompt_version_id
                 WHERE a.ticket_id = ? AND a.tenant_id = ?
                 ORDER BY a.created_at DESC
                 LIMIT 1
                """,
                (rs, rowNum) -> new AnalysisRow(
                        rs.getString("status"),
                        rs.getString("signals"),
                        // NUMERIC(4,3) comes back as BigDecimal; the driver refuses to
                        // hand it over as a Double, so the conversion is explicit.
                        rs.getBigDecimal("confidence") == null ? null
                                : rs.getBigDecimal("confidence").doubleValue(),
                        rs.getString("model_id"),
                        rs.getInt("tokens_in"),
                        rs.getInt("tokens_out"),
                        rs.getLong("cost_micros"),
                        rs.getInt("latency_ms"),
                        rs.getInt("attempt"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getString("prompt_name") + "@" + rs.getInt("prompt_version")),
                ticketId, tenantId);

        if (!analyses.isEmpty()) {
            AnalysisRow row = analyses.get(0);
            if ("OK".equals(row.status())) {
                return ready(ticketId, row);
            }
            // The model replied and the reply was unusable — a schema violation the
            // repair pass could not fix, or a refusal. Distinct from "we never got an
            // answer", and the reason says which.
            return AnalysisResponse.unavailable(ticketId, row.status(), row.attempt());
        }

        return fromQueueState(tenantId, ticketId);
    }

    /**
     * No result yet, so the answer depends on whether anything is still trying.
     */
    private AnalysisResponse fromQueueState(Long tenantId, Long ticketId) {
        List<QueueRow> events = jdbc.query("""
                SELECT status, attempts, created_at
                  FROM outbox_event
                 WHERE aggregate_type = 'TICKET' AND aggregate_id = ? AND tenant_id = ?
                   AND event_type IN ('TICKET_CREATED', 'TICKET_RETRIAGE_REQUESTED')
                 ORDER BY id DESC
                 LIMIT 1
                """,
                (rs, rowNum) -> new QueueRow(rs.getString("status"), rs.getInt("attempts"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                ticketId, tenantId);

        if (events.isEmpty()) {
            // Nothing was ever queued. A ticket created before the async pipeline
            // existed, or one whose event has been pruned. Honest answer: nobody is
            // working on this, so a human should.
            return AnalysisResponse.unavailable(ticketId, "NOT_QUEUED", 0);
        }

        QueueRow event = events.get(0);
        String status = event.status();
        OffsetDateTime enqueuedAt = event.enqueuedAt();
        int attempts = event.attempts();

        return switch (status) {
            case "PENDING", "IN_FLIGHT" ->
                    AnalysisResponse.processing(ticketId, enqueuedAt, RETRY_AFTER_SECONDS);
            case "DEAD" -> AnalysisResponse.unavailable(ticketId, "PROVIDER_ERROR", attempts);
            // DONE with no analysis row: the worker finished without producing one, which
            // it only does when the tenant's policy or budget blocked the call. Saying
            // PROCESSING here would have the client poll for ever.
            default -> AnalysisResponse.unavailable(ticketId, "NO_ANALYSIS_PRODUCED", attempts);
        };
    }

    private AnalysisResponse ready(Long ticketId, AnalysisRow row) {
        return new AnalysisResponse(
                ticketId,
                "READY",
                null,
                null,
                readSignals(row.signals()),
                row.confidence(),
                row.promptVersion(),
                row.modelId(),
                row.tokensIn(),
                row.tokensOut(),
                row.costMicros(),
                row.latencyMs(),
                row.completedAt(),
                null, null, null);
    }

    /** The latest analysis row, already out of JDBC types. */
    private record AnalysisRow(String status, String signals, Double confidence, String modelId,
                               int tokensIn, int tokensOut, long costMicros, int latencyMs,
                               int attempt, OffsetDateTime completedAt, String promptVersion) {
    }

    /** What the queue knows about this ticket's triage job. */
    private record QueueRow(String status, int attempts, OffsetDateTime enqueuedAt) {
    }

    /**
     * The signals, as stored.
     *
     * <p>Returned as a map rather than a typed record on purpose: {@code TriageSignals}
     * is versioned with the prompt, and an old row was written against an older shape. A
     * typed read would fail on exactly the historical rows somebody is looking at
     * <i>because</i> they are historical.
     */
    private Map<String, Object> readSignals(String signals) {
        if (signals == null) {
            return Map.of();
        }
        return objectMapper.readValue(signals,
                new TypeReference<Map<String, Object>>() { });
    }
}
