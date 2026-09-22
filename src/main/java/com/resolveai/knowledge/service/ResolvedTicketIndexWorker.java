package com.resolveai.knowledge.service;

import com.resolveai.knowledge.domain.DocumentSource;
import com.resolveai.platform.ai.pii.PiiRedactor;
import com.resolveai.platform.outbox.EventType;
import com.resolveai.platform.outbox.NonRetryableException;
import com.resolveai.platform.outbox.OutboxEvent;
import com.resolveai.platform.outbox.Worker;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a resolved ticket into a knowledge document, subject to three quality gates.
 * Doc 15 Task 25.
 *
 * <h2>Why three gates, and why they exist before any tuning</h2>
 *
 * <p>Indexing every resolved ticket unconditionally would poison retrieval rather than
 * improve it:
 *
 * <ul>
 *   <li><b>Resolution text under 100 characters</b> — "fixed", "done", "resolved via
 *       call" teaches retrieval nothing and dilutes genuinely useful precedents with
 *       noise that happens to embed somewhere in the same neighbourhood.
 *   <li><b>Linked to an incident</b> — thirty-eight tickets from one outage, each
 *       resolved with a near-identical note, would add thirty-eight near-duplicate
 *       documents. One good precedent beats thirty-eight copies of it competing for the
 *       same retrieval slots.
 *   <li><b>Reopened</b> — a resolution that did not hold is a bad precedent by
 *       definition. Indexing it teaches the next agent to repeat a fix that already
 *       failed once.
 * </ul>
 *
 * <h2>PII redaction, and why it cannot be skipped here of all places</h2>
 *
 * <p>A resolved ticket's messages contain a real customer's name, order references,
 * sometimes card digits — exactly the personal data {@code PiiRedactor} exists to strip
 * before anything leaves for a model. Skipping it here would mean that data does not
 * merely leave the building once (as it did during triage) — it becomes <i>permanently
 * retrievable</i>, surfaced in every future draft that happens to match on it. The
 * redacted form is what gets indexed; nothing about the original is stored in the
 * knowledge document.
 *
 * <h2>Tier is always {@code PRECEDENT}, never {@code AUTHORITATIVE}</h2>
 *
 * <p>{@code DocumentSource.RESOLVED_TICKET.tier()} already encodes this — see that
 * enum — because a resolution is evidence a fix worked once, not documented policy, and
 * the retrieval tier boost must not let a single anecdote outrank a runbook.
 */
@Component
public class ResolvedTicketIndexWorker implements Worker {

    private static final Logger log = LoggerFactory.getLogger(ResolvedTicketIndexWorker.class);

    /** Below this, a resolution teaches nothing and only adds noise. */
    private static final int MIN_RESOLUTION_LENGTH = 100;

    private final TransactionTemplate txTemplate;
    private final JdbcTemplate jdbc;
    private final PiiRedactor redactor;
    private final KnowledgeDocumentService documents;
    private final ObjectMapper objectMapper;

    public ResolvedTicketIndexWorker(TransactionTemplate txTemplate, JdbcTemplate jdbc,
                                     PiiRedactor redactor, KnowledgeDocumentService documents,
                                     ObjectMapper objectMapper) {
        this.txTemplate = txTemplate;
        this.jdbc = jdbc;
        this.redactor = redactor;
        this.documents = documents;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<EventType> handles() {
        return Set.of(EventType.TICKET_RESOLVED);
    }

    @Override
    public Duration visibilityTimeout() {
        return Duration.ofMinutes(5);
    }

    @Override
    public int batchSize() {
        return 5;
    }

    private record TicketSnapshot(Long id, String reference, String subject, String body,
                                  int reopenCount, boolean incidentLinked, String resolution) {
    }

    @Override
    public void process(OutboxEvent event) {
        Long ticketId = readTicketId(event);

        TicketSnapshot snapshot = txTemplate.execute(status -> load(ticketId));
        if (snapshot == null) {
            throw new NonRetryableException("Ticket " + ticketId + " no longer exists");
        }

        String skipReason = skipReasonFor(snapshot);
        if (skipReason != null) {
            log.info("Skipping resolved-ticket indexing for {}: {}", snapshot.reference(),
                    skipReason);
            return;
        }

        // Redaction, then creation, then — same as every other document — indexing is
        // queued asynchronously by KnowledgeDocumentService.create rather than performed
        // inline here. Chunking and embedding a precedent document is identical work to
        // chunking and embedding any other document, and IndexWorker already does it
        // correctly; duplicating that logic here would be a second implementation of the
        // same pipeline.
        String redactedBody = redactor.redact(event.tenantId(), snapshot.id(),
                snapshot.subject() + "\n\n" + snapshot.body() + "\n\nResolution:\n"
                + snapshot.resolution()).redactedText();

        String title = snapshot.reference() + ": " + snapshot.subject();
        txTemplate.executeWithoutResult(status ->
                documents.create(DocumentSource.RESOLVED_TICKET, title, redactedBody,
                        "/tickets/" + snapshot.id()));

        log.info("Indexed resolved ticket {} as a knowledge document ({} chars, PII-redacted)",
                snapshot.reference(), redactedBody.length());
    }

    /** @return the reason to skip, or {@code null} if all three gates pass. */
    private String skipReasonFor(TicketSnapshot snapshot) {
        if (snapshot.resolution() == null
                || snapshot.resolution().length() < MIN_RESOLUTION_LENGTH) {
            return "resolution text under " + MIN_RESOLUTION_LENGTH + " characters";
        }
        if (snapshot.incidentLinked()) {
            return "ticket is linked to an incident";
        }
        if (snapshot.reopenCount() > 0) {
            return "ticket was reopened " + snapshot.reopenCount() + " time(s)";
        }
        return null;
    }

    private TicketSnapshot load(Long ticketId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.id, t.reference, t.subject, t.body, t.reopen_count,
                       EXISTS (SELECT 1 FROM incident_ticket it
                                WHERE it.ticket_id = t.id AND it.detached_at IS NULL)
                           AS incident_linked,
                       (SELECT m.body FROM ticket_message m
                         WHERE m.ticket_id = t.id AND m.visibility = 'PUBLIC'
                         ORDER BY m.created_at DESC LIMIT 1) AS resolution
                  FROM ticket t
                 WHERE t.id = ?
                """, ticketId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        return new TicketSnapshot((Long) row.get("id"), (String) row.get("reference"),
                (String) row.get("subject"), (String) row.get("body"),
                (Integer) row.get("reopen_count"), (Boolean) row.get("incident_linked"),
                (String) row.get("resolution"));
    }

    @SuppressWarnings("unchecked")
    private Long readTicketId(OutboxEvent event) {
        try {
            Map<String, Object> body = objectMapper.readValue(event.payload(), Map.class);
            Object id = body.get("ticketId");
            return id == null ? event.aggregateId() : Long.valueOf(String.valueOf(id));
        } catch (RuntimeException e) {
            throw new NonRetryableException("Unreadable payload on outbox event "
                    + event.id(), e);
        }
    }
}
