package com.resolveai.triage;

import com.resolveai.ticketing.domain.Priority;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The triage pipeline's own writes, in plain SQL.
 *
 * <h2>Why JdbcTemplate and not entities</h2>
 *
 * <p>{@code ai_analysis} and {@code priority_decision} are <b>append-only records of what
 * happened</b>. Nothing ever loads one, changes a field and saves it; they are written
 * once by a worker and read back as JSON by an endpoint. An entity for a row with that
 * life cycle buys a first-level cache nobody wants, dirty checking on rows that must
 * never be dirty, and a mapping for two JSONB columns — in exchange for nothing.
 *
 * <p>It also keeps the JSONB honest. The signals are stored exactly as the model's
 * parsed result serialises, and the rationale exactly as the policy produced it, with no
 * chance of a Hibernate type converter quietly reshaping either between versions. A
 * rationale that cannot be read back in the shape it was written is not an audit trail.
 */
@Repository
public class TriageRepository {

    private final JdbcTemplate jdbc;

    public TriageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records one model call, successful or not.
     *
     * <p><b>Returns empty on a duplicate rather than throwing.</b>
     * {@code uq_analysis_ticket_prompt} covers {@code (ticket_id, prompt_version_id,
     * attempt)}, and a redelivered event — a worker that crashed after the model
     * answered but before the outbox row was marked done — arrives with the same
     * attempt number and loses the insert. That is the constraint doing its job: the
     * work is already recorded, so the second run has nothing to do and should not
     * dead-letter the event for succeeding twice.
     *
     * @param attempt from the event payload, not from a counter here. Deriving it from
     *                {@code MAX(attempt) + 1} would give a redelivery a <i>new</i>
     *                number, and the uniqueness constraint would then permit exactly the
     *                duplicate it exists to prevent.
     */
    public Optional<Long> insertAnalysis(Long tenantId, Long ticketId, Long promptVersionId,
                                         String modelId, String signalsJson, Double confidence,
                                         int tokensIn, int tokensOut, long costMicros,
                                         int latencyMs, int attempt, String status) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    INSERT INTO ai_analysis (ticket_id, tenant_id, prompt_version_id, model_id,
                                             signals, confidence, tokens_in, tokens_out,
                                             cost_micros, latency_ms, attempt, status)
                    VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """, Long.class, ticketId, tenantId, promptVersionId, modelId, signalsJson,
                    confidence, tokensIn, tokensOut, costMicros, latencyMs, attempt, status));
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    /**
     * Records the priority decision and the full argument for it.
     *
     * @param aiAnalysisId {@code null} when there was no usable analysis — a provider
     *                     outage still produces a decision, because the fallback has to
     *                     be explainable too.
     */
    public Long insertDecision(Long tenantId, Long ticketId, Long aiAnalysisId,
                               String policyVersion, String inputSignalsJson,
                               Priority priority, String rationaleJson) {
        return jdbc.queryForObject("""
                INSERT INTO priority_decision (ticket_id, tenant_id, ai_analysis_id,
                                               policy_version, input_signals,
                                               computed_priority, rationale)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS jsonb))
                RETURNING id
                """, Long.class, ticketId, tenantId, aiAnalysisId, policyVersion,
                inputSignalsJson, priority.name(), rationaleJson);
    }

    /**
     * The decision currently in force, which is the most recent one.
     *
     * <p><b>The tenant predicate is explicit and is not redundant.</b> Callers reach
     * this through {@code TicketAccess.loadVisible}, which already 404s a foreign
     * ticket — so today the predicate changes no behaviour. It is here because
     * {@code @TenantId} does not reach native SQL, and "safe because every caller
     * remembers to check first" is a property that holds until the first caller that
     * does not. A leak of a priority rationale is a leak of another tenant's ticket
     * text, and the cost of the predicate is one line.
     */
    public Optional<DecisionRow> latestDecision(Long tenantId, Long ticketId) {
        List<DecisionRow> rows = jdbc.query("""
                SELECT policy_version, input_signals, computed_priority, rationale, decided_at
                  FROM priority_decision
                 WHERE ticket_id = ? AND tenant_id = ?
                 ORDER BY decided_at DESC, id DESC
                 LIMIT 1
                """,
                (rs, rowNum) -> new DecisionRow(
                        rs.getString("policy_version"),
                        rs.getString("input_signals"),
                        Priority.valueOf(rs.getString("computed_priority")),
                        rs.getString("rationale"),
                        rs.getObject("decided_at", OffsetDateTime.class)),
                ticketId, tenantId);
        return rows.stream().findFirst();
    }

    /** An agent's override, kept as its own row because it is training data. */
    public void insertOverride(Long tenantId, Long ticketId, Priority from, Priority to,
                               String reason, Long userId) {
        jdbc.update("""
                INSERT INTO priority_override (ticket_id, tenant_id, from_priority,
                                               to_priority, reason, overridden_by)
                VALUES (?, ?, ?, ?, ?, ?)
                """, ticketId, tenantId, from.name(), to.name(), reason, userId);
    }

    /** Whether a triage job for this ticket is still in the queue. */
    public boolean hasPendingTriage(Long tenantId, Long ticketId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox_event
                 WHERE aggregate_type = 'TICKET' AND aggregate_id = ? AND tenant_id = ?
                   AND event_type IN ('TICKET_CREATED', 'TICKET_RETRIAGE_REQUESTED')
                   AND status IN ('PENDING', 'IN_FLIGHT')
                """, Integer.class, ticketId, tenantId);
        return count != null && count > 0;
    }

    /** The next attempt number for a retriage: one past the highest already recorded. */
    public int nextAttempt(Long tenantId, Long ticketId) {
        Integer max = jdbc.queryForObject("""
                SELECT COALESCE(MAX(attempt), 0) FROM ai_analysis
                 WHERE ticket_id = ? AND tenant_id = ?
                """, Integer.class, ticketId, tenantId);
        return (max == null ? 0 : max) + 1;
    }

    /** How many retriages were asked for since {@code since}, for the rate limit. */
    public int countRetriagesSince(Long tenantId, Long ticketId, OffsetDateTime since) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox_event
                 WHERE aggregate_type = 'TICKET' AND aggregate_id = ? AND tenant_id = ?
                   AND event_type = 'TICKET_RETRIAGE_REQUESTED'
                   AND created_at >= ?
                """, Integer.class, ticketId, tenantId, since);
        return count == null ? 0 : count;
    }

    /** A persisted decision, still carrying its two JSONB columns as text. */
    public record DecisionRow(String policyVersion, String inputSignals, Priority priority,
                              String rationale, OffsetDateTime decidedAt) {
    }
}
