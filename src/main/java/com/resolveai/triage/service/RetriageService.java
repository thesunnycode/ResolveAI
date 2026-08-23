package com.resolveai.triage.service;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.iam.security.ResolvePrincipal;
import com.resolveai.platform.outbox.EventType;
import com.resolveai.platform.outbox.OutboxPublisher;
import com.resolveai.platform.time.DatabaseClock;
import com.resolveai.ticketing.domain.Ticket;
import com.resolveai.ticketing.service.TicketAccess;
import com.resolveai.triage.TriageRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-drives triage for one ticket.
 *
 * <h2>What it is for</h2>
 *
 * <p>Two situations, both real: a provider outage left a batch of tickets with
 * {@code UNAVAILABLE} analyses, and a prompt fix means an old classification is known to
 * be wrong. In both, the alternative to this endpoint is an agent doing by hand what the
 * system can do for a fraction of a cent.
 *
 * <h2>A new attempt, not a replacement</h2>
 *
 * <p>The event carries {@code attempt = n + 1}, and
 * {@code uq_analysis_ticket_prompt (ticket_id, prompt_version_id, attempt)} therefore
 * lets the retry write a <b>second row</b> rather than colliding with the first. The old
 * analysis stays, which is the point: "the model said PAYMENT in March and BILLING in
 * June after the prompt change" is the evidence that the prompt change did something,
 * and overwriting would destroy it.
 *
 * <p>It is also why {@code GET /analysis} orders by {@code created_at DESC} and returns
 * the <i>latest</i> row. A retriage that produced a better answer which the endpoint
 * then declined to show would be a particularly annoying kind of working.
 */
@Service
public class RetriageService {

    private static final Logger log = LoggerFactory.getLogger(RetriageService.class);

    /**
     * Five per hour per ticket.
     *
     * <p>Not a throughput limit — it is a loop guard. Retriage costs a model call, and
     * an agent clicking "retry" against a provider that is down can otherwise spend a
     * tenant's budget on an outage that no amount of retrying will fix. Per ticket
     * rather than per user, because the pathological case is one stuck ticket, not one
     * busy agent.
     */
    private static final int MAX_PER_HOUR = 5;

    private final TicketAccess access;
    private final TriageRepository triage;
    private final OutboxPublisher outbox;
    private final DatabaseClock clock;
    private final MeterRegistry metrics;

    public RetriageService(TicketAccess access, TriageRepository triage,
                           OutboxPublisher outbox, DatabaseClock clock,
                           MeterRegistry metrics) {
        this.access = access;
        this.triage = triage;
        this.outbox = outbox;
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * Queues a fresh triage.
     *
     * @return the attempt number the new analysis will carry
     * @throws ApiException {@code 409 TRIAGE_IN_PROGRESS} when one is already queued,
     *                      {@code 429 RATE_LIMITED} past five in an hour
     */
    @Transactional
    public Map<String, Object> retriage(ResolvePrincipal principal, Long ticketId) {
        access.requireAgentOrAbove(principal, "retriage a ticket");
        Ticket ticket = access.loadVisible(principal, ticketId);

        if (triage.hasPendingTriage(principal.tenantId(), ticketId)) {
            // 409 rather than silently succeeding. Queueing a second job would mean two
            // workers classifying the same ticket concurrently, paying twice, and one of
            // them losing the insert — all to produce the answer the first one was about
            // to produce anyway.
            throw new ApiException(ErrorCode.TRIAGE_IN_PROGRESS,
                    "Triage is already queued for this ticket.");
        }

        int recent = triage.countRetriagesSince(principal.tenantId(), ticketId,
                clock.now().minus(Duration.ofHours(1)));
        if (recent >= MAX_PER_HOUR) {
            throw new ApiException(ErrorCode.RATE_LIMITED,
                    "This ticket has been retriaged " + recent + " times in the last hour. "
                    + "Wait before trying again.");
        }

        int attempt = triage.nextAttempt(principal.tenantId(), ticketId);
        outbox.publish("TICKET", ticketId, EventType.TICKET_RETRIAGE_REQUESTED,
                Map.of("ticketId", ticketId, "attempt", attempt,
                        "requestedBy", principal.userId()));

        metrics.counter("triage.retriage.requested").increment();
        log.info("Retriage of ticket {} queued as attempt {} by user {}",
                ticket.getReference(), attempt, principal.userId());

        return Map.of("ticketId", ticketId, "status", "PROCESSING", "attempt", attempt,
                "links", Map.of("analysis", "/api/v1/tickets/" + ticketId + "/analysis"));
    }
}
