package com.resolveai.triage;

import com.resolveai.platform.tenant.TenantContext;
import com.resolveai.platform.time.DatabaseClock;
import com.resolveai.ticketing.domain.Priority;
import com.resolveai.ticketing.domain.Ticket;
import com.resolveai.ticketing.domain.TicketEventType;
import com.resolveai.ticketing.repository.TicketRepository;
import com.resolveai.ticketing.service.SlaLifecycle;
import com.resolveai.ticketing.service.TicketEventRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Gives a default SLA to tickets whose triage never succeeded.
 *
 * <h2>The gap this closes, and how it came to exist</h2>
 *
 * <p>Phase 6A moved {@code sla.start()} out of ticket creation, because a clock should
 * measure the promise that triage decides. Phase 6C put it at the end of
 * {@link TriageWorker}'s write transaction. Both steps are right, and together they
 * created a hole that neither one has on its own: <b>if triage never succeeds, the ticket
 * has no SLA at all</b> — no first-response clock, no resolution clock, nothing in the
 * at-risk list, no escalation, ever.
 *
 * <p>That is worse than it sounds, because the failure is completely silent. The ticket
 * looks ordinary in the queue. Nothing logs. The SLA dashboard is, if anything, greener
 * than before, because untracked tickets cannot breach. During a two-hour provider
 * outage every ticket raised is permanently invisible to the entire SLA system, and the
 * first sign is a customer asking why nobody replied.
 *
 * <p><b>This is the kind of gap that only appears when a call moves between
 * transactions</b>, and it is worth stating plainly rather than quietly patching: the
 * bug was not in either transaction, it was in the space between them.
 *
 * <h2>Why P3 and not the real answer</h2>
 *
 * <p>P3 is the default target, not a guess at this ticket's priority. The honest position
 * is "we do not know, so track it on the standard promise until a human says otherwise" —
 * and the audit event records that the priority came from the fallback rather than from a
 * decision, so nobody reading the ticket later mistakes it for one. Defaulting to P1
 * would flood the escalation ladder during exactly the outage that caused the problem;
 * defaulting to P4 would hide the tickets that most need attention.
 */
@Component
public class SlaFallbackSweeper {

    private static final Logger log = LoggerFactory.getLogger(SlaFallbackSweeper.class);

    /** The priority applied when nothing decided one. */
    private static final Priority DEFAULT_PRIORITY = Priority.P3;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;
    private final TicketRepository tickets;
    private final TicketEventRecorder eventRecorder;
    private final SlaLifecycle sla;
    private final DatabaseClock clock;
    private final MeterRegistry metrics;
    private final Duration grace;
    private final int batchSize;

    public SlaFallbackSweeper(JdbcTemplate jdbc, TransactionTemplate txTemplate,
                              TicketRepository tickets, TicketEventRecorder eventRecorder,
                              SlaLifecycle sla, DatabaseClock clock, MeterRegistry metrics,
                              @Value("${resolveai.workers.sla-fallback-grace-minutes:10}")
                              long graceMinutes,
                              @Value("${resolveai.workers.sla-fallback-batch-size:100}")
                              int batchSize) {
        this.jdbc = jdbc;
        this.txTemplate = txTemplate;
        this.tickets = tickets;
        this.eventRecorder = eventRecorder;
        this.sla = sla;
        this.clock = clock;
        this.metrics = metrics;
        this.grace = Duration.ofMinutes(graceMinutes);
        this.batchSize = batchSize;
    }

    /**
     * Every five minutes, with a ten-minute grace period.
     *
     * <p>The grace is what keeps the sweeper out of triage's way. Triage normally
     * finishes in seconds, and the retry ladder gives it a few minutes more; sweeping at
     * ten minutes means the fallback only ever sees tickets whose triage has genuinely
     * given up or is stuck, not ones that are merely mid-flight. Sweep immediately and
     * the sweeper and the worker race to start the same clocks — which
     * {@code uq_sla_ticket_kind} would survive, but only by one of them losing an insert
     * it had no business attempting.
     */
    @Scheduled(fixedDelayString = "${resolveai.workers.sla-fallback-interval-ms:300000}")
    public void sweep() {
        try {
            int repaired = sweepOnce();
            if (repaired > 0) {
                log.warn("SLA fallback started default clocks on {} untracked ticket(s)",
                        repaired);
            }
        } catch (RuntimeException e) {
            // A scheduled method that throws is never rescheduled by some schedulers, and
            // this one going quiet is exactly the silence it exists to prevent.
            log.error("SLA fallback sweep failed; the next sweep will retry", e);
        }
    }

    /**
     * One pass. Returns how many tickets were repaired.
     *
     * <p>Public and returning a count so tests can drive it directly rather than waiting
     * for the schedule — a test that sleeps for five minutes is not a test.
     */
    public int sweepOnce() {
        OffsetDateTime cutoff = clock.now().minus(grace);

        // Cross-tenant by design: this runs outside any request, so the tenant comes off
        // each row rather than from a context. The query is native because @TenantId
        // would scope it to whatever tenant happened to be set, which for a scheduled
        // job is none.
        List<Untracked> untracked = jdbc.query("""
                SELECT t.id, t.tenant_id
                  FROM ticket t
                 WHERE t.created_at < ?
                   AND t.status NOT IN ('RESOLVED', 'CLOSED')
                   AND NOT EXISTS (SELECT 1 FROM sla_record s
                                    WHERE s.ticket_id = t.id AND s.state <> 'CANCELLED')
                 ORDER BY t.created_at
                 LIMIT ?
                """,
                (rs, rowNum) -> new Untracked(rs.getLong("id"), rs.getLong("tenant_id")),
                cutoff, batchSize);

        int repaired = 0;
        for (Untracked row : untracked) {
            // The tenant is set BEFORE the transaction opens. Hibernate resolves the
            // tenant when the session opens, not per statement, so setting it inside
            // produces "entity not found" on rows that plainly exist.
            Boolean done = TenantContext.callAs(row.tenantId(),
                    () -> txTemplate.execute(status -> applyFallback(row.ticketId())));
            if (Boolean.TRUE.equals(done)) {
                repaired++;
                metrics.counter("sla.fallback.applied").increment();
            }
        }
        return repaired;
    }

    private boolean applyFallback(Long ticketId) {
        Ticket ticket = tickets.findById(ticketId).orElse(null);
        if (ticket == null) {
            return false;
        }

        if (!ticket.getPriority().isTriaged()) {
            ticket.setPriority(DEFAULT_PRIORITY);
            // Recorded as its own event with an explicit source, so the timeline says
            // "the system defaulted this" rather than implying somebody decided it.
            eventRecorder.record(ticket, TicketEventType.PRIORITY_CHANGED,
                    Priority.UNTRIAGED.name(), DEFAULT_PRIORITY.name(),
                    Map.of("source", "SLA_FALLBACK",
                           "reason", "triage did not complete; default target applied"));
        }

        sla.start(ticket);
        tickets.saveAndFlush(ticket);
        return true;
    }

    private record Untracked(Long ticketId, Long tenantId) {
    }
}
