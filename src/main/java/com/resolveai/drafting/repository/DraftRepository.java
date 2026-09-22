package com.resolveai.drafting.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * The drafting pipeline's own writes and reads, in plain SQL — the same choice
 * {@code TriageRepository} makes for {@code ai_analysis} and {@code priority_decision},
 * and for the identical reason: a draft, its claims, their citations and the unresolved
 * aspects are written once and read back whole. Nothing here is loaded, mutated field by
 * field and saved; an entity graph for that life cycle would buy dirty checking and a
 * first-level cache nobody wants, over five tables, for no benefit.
 */
@Repository
public class DraftRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DraftRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ── Creation and pending check ─────────────────────────────────────────

    public boolean hasPendingDraft(Long ticketId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM draft WHERE ticket_id = ? AND status = 'PENDING'",
                Integer.class, ticketId);
        return count != null && count > 0;
    }

    public Long insertPending(Long ticketId, Long tenantId, Long promptVersionId,
                              String modelId, Long requestedBy, String instruction) {
        return jdbc.queryForObject("""
                INSERT INTO draft (ticket_id, tenant_id, prompt_version_id, model_id,
                                   requested_by, status, instruction)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?)
                RETURNING id
                """, Long.class, ticketId, tenantId, promptVersionId, modelId, requestedBy,
                instruction);
    }

    public void markShown(Long draftId, double coverage, String assembledText,
                          int tokensIn, int tokensOut, long costMicros, long latencyMs) {
        jdbc.update("""
                UPDATE draft
                   SET status = 'SHOWN', coverage = ?, assembled_text = ?,
                       tokens_in = ?, tokens_out = ?, cost_micros = ?, latency_ms = ?
                 WHERE id = ?
                """, coverage, assembledText, tokensIn, tokensOut, costMicros, latencyMs,
                draftId);
    }

    public void markSuppressed(Long draftId, String status, double coverage,
                               String suppressionReason, int tokensIn, int tokensOut,
                               long costMicros, long latencyMs) {
        jdbc.update("""
                UPDATE draft
                   SET status = ?, coverage = ?, assembled_text = NULL,
                       suppression_reason = ?,
                       tokens_in = ?, tokens_out = ?, cost_micros = ?, latency_ms = ?
                 WHERE id = ?
                """, status, coverage, suppressionReason, tokensIn, tokensOut, costMicros,
                latencyMs, draftId);
    }

    public void markFailed(Long draftId) {
        jdbc.update("UPDATE draft SET status = 'FAILED' WHERE id = ?", draftId);
    }

    // ── Claims and citations ───────────────────────────────────────────────

    public Long insertClaim(Long draftId, int ordinal, String text, String verdict,
                            String verifierModel, boolean kept, String rejectionReason) {
        try {
            return jdbc.queryForObject("""
                    INSERT INTO draft_claim (draft_id, ordinal, text, verdict, verifier_model,
                                             kept, rejection_reason)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """, Long.class, draftId, ordinal, text, verdict, verifierModel, kept,
                    rejectionReason);
        } catch (DuplicateKeyException e) {
            // uq_claim_ordinal: a redelivered DRAFT_REQUESTED event after a crash between
            // the write completing and the outbox row being marked done. The work is
            // already recorded; nothing to insert twice.
            return null;
        }
    }

    public void insertCitation(Long claimId, Long chunkId, int charStart, int charEnd) {
        jdbc.update("""
                INSERT INTO draft_claim_citation (draft_claim_id, chunk_id, char_start, char_end)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (draft_claim_id, chunk_id, char_start) DO NOTHING
                """, claimId, chunkId, charStart, charEnd);
    }

    public void insertUnresolvedAspect(Long draftId, int ordinal, String text) {
        jdbc.update("""
                INSERT INTO draft_unresolved_aspect (draft_id, ordinal, text)
                VALUES (?, ?, ?)
                ON CONFLICT (draft_id, ordinal) DO NOTHING
                """, draftId, ordinal, text);
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    public record DraftRow(Long id, Long ticketId, Long tenantId, String status,
                           Double coverage, String assembledText, String suppressionReason,
                           String instruction, String promptVersionLabel, String modelId,
                           int tokensIn, int tokensOut, long costMicros, long latencyMs,
                           OffsetDateTime createdAt) {
    }

    public Optional<DraftRow> findById(Long draftId) {
        List<DraftRow> rows = jdbc.query("""
                SELECT d.id, d.ticket_id, d.tenant_id, d.status, d.coverage,
                       d.assembled_text, d.suppression_reason, d.instruction, d.model_id,
                       d.tokens_in, d.tokens_out, d.cost_micros, d.latency_ms, d.created_at,
                       p.name || '@' || p.version AS prompt_label
                  FROM draft d
                  JOIN prompt_version p ON p.id = d.prompt_version_id
                 WHERE d.id = ?
                """, (rs, rowNum) -> new DraftRow(
                        rs.getLong("id"), rs.getLong("ticket_id"), rs.getLong("tenant_id"),
                        rs.getString("status"),
                        // NUMERIC(4,3) comes back as BigDecimal, not Double — the same
                        // trap ai_analysis.confidence would hit with a bare cast.
                        rs.getObject("coverage") == null ? null : rs.getDouble("coverage"),
                        rs.getString("assembled_text"), rs.getString("suppression_reason"),
                        rs.getString("instruction"), rs.getString("prompt_label"),
                        rs.getString("model_id"), rs.getInt("tokens_in"),
                        rs.getInt("tokens_out"), rs.getLong("cost_micros"),
                        rs.getLong("latency_ms"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                draftId);
        return rows.stream().findFirst();
    }

    public record ClaimRow(Long id, int ordinal, String text, String verdict, boolean kept,
                           String rejectionReason) {
    }

    public List<ClaimRow> findClaims(Long draftId) {
        return jdbc.query("""
                SELECT id, ordinal, text, verdict, kept, rejection_reason
                  FROM draft_claim WHERE draft_id = ? ORDER BY ordinal
                """, (rs, rowNum) -> new ClaimRow(rs.getLong("id"), rs.getInt("ordinal"),
                        rs.getString("text"), rs.getString("verdict"), rs.getBoolean("kept"),
                        rs.getString("rejection_reason")),
                draftId);
    }

    public record CitationRow(Long chunkId, Long documentId, String documentTitle,
                              String source, int charStart, int charEnd, String snippet) {
    }

    public List<CitationRow> findCitations(Long claimId) {
        return jdbc.query("""
                SELECT cit.chunk_id, kc.document_id, kd.title AS document_title, kd.source,
                       cit.char_start, cit.char_end,
                       substring(kc.text from cit.char_start + 1 for
                                 (cit.char_end - cit.char_start)) AS snippet
                  FROM draft_claim_citation cit
                  JOIN knowledge_chunk kc ON kc.id = cit.chunk_id
                  JOIN knowledge_document kd ON kd.id = kc.document_id
                 WHERE cit.draft_claim_id = ?
                """, (rs, rowNum) -> new CitationRow(rs.getLong("chunk_id"),
                        rs.getLong("document_id"), rs.getString("document_title"),
                        rs.getString("source"), rs.getInt("char_start"), rs.getInt("char_end"),
                        rs.getString("snippet")),
                claimId);
    }

    public List<String> findUnresolvedAspects(Long draftId) {
        return jdbc.queryForList(
                "SELECT text FROM draft_unresolved_aspect WHERE draft_id = ? ORDER BY ordinal",
                String.class, draftId);
    }

    // ── Actions ─────────────────────────────────────────────────────────────

    public record ActionRow(String action, Integer editDistance, OffsetDateTime createdAt) {
    }

    public Optional<ActionRow> findAction(Long draftId) {
        return jdbc.query("SELECT action, edit_distance, created_at FROM agent_draft_action "
                          + "WHERE draft_id = ?",
                (rs, rowNum) -> new ActionRow(rs.getString("action"),
                        (Integer) rs.getObject("edit_distance"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                draftId)
                .stream().findFirst();
    }

    /** @return true if this call recorded it; false if one was already recorded ({@code 409}). */
    public boolean insertAction(Long draftId, Long agentId, String action, Integer editDistance,
                                String finalText) {
        try {
            jdbc.update("""
                    INSERT INTO agent_draft_action (draft_id, agent_id, action, edit_distance,
                                                    final_text)
                    VALUES (?, ?, ?, ?, ?)
                    """, draftId, agentId, action, editDistance, finalText);
            return true;
        } catch (DuplicateKeyException e) {
            // uq_draft_action: one terminal action per draft.
            return false;
        }
    }
}
