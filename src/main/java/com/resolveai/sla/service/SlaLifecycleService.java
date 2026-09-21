package com.resolveai.sla.service;

import com.resolveai.iam.domain.PlanTier;
import com.resolveai.iam.repository.TenantRepository;
import com.resolveai.sla.domain.CalendarSpec;
import com.resolveai.sla.domain.PauseReason;
import com.resolveai.sla.domain.SlaKind;
import com.resolveai.sla.domain.SlaRecord;
import com.resolveai.sla.domain.SlaState;
import com.resolveai.sla.repository.SlaRecordRepository;
import com.resolveai.ticketing.domain.Ticket;
import com.resolveai.ticketing.domain.TicketEventType;
import com.resolveai.ticketing.service.SlaLifecycle;
import com.resolveai.ticketing.service.TicketEventRecorder;
import com.resolveai.platform.time.DatabaseClock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The real implementation of the port ticketing calls. Task 27.
 *
 * <p>Its mere existence switches every call site over: {@code NoOpSlaLifecycle} is
 * registered {@code @ConditionalOnMissingBean}, so this bean silently replaces it and not
 * one line in {@code TicketService} changes. That was the point of building the port in
 * 5A — the alternative was six {@code // TODO: call slaService} comments and at least one
 * of them being missed.
 *
 * <p><b>Everything is {@code MANDATORY}.</b> These run inside the transaction of the ticket
 * change that triggered them. A resolve that commits while its clock stop rolls back
 * leaves a resolved ticket with a clock still running, which the poller will then breach.
 */
@Service
public class SlaLifecycleService implements SlaLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SlaLifecycleService.class);

    private final SlaClockService clocks;
    private final SlaRecordRepository records;
    private final SlaCalculator calculator;
    private final CalendarService calendars;
    private final TenantRepository tenants;
    private final TicketEventRecorder eventRecorder;
    private final BreachPredictor predictor;
    private final DatabaseClock clock;

    public SlaLifecycleService(SlaClockService clocks, SlaRecordRepository records,
                               SlaCalculator calculator, CalendarService calendars,
                               TenantRepository tenants, TicketEventRecorder eventRecorder,
                               BreachPredictor predictor, DatabaseClock clock) {
        this.clocks = clocks;
        this.records = records;
        this.calculator = calculator;
        this.calendars = calendars;
        this.tenants = tenants;
        this.eventRecorder = eventRecorder;
        this.predictor = predictor;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void start(Ticket ticket) {
        OffsetDateTime now = clock.now();
        List<SlaRecord> started = clocks.start(ticket, planTierOf(ticket), now);
        for (SlaRecord record : started) {
            eventRecorder.record(ticket, TicketEventType.SLA_STARTED, null,
                    record.getKind().name(),
                    Map.of("targetMinutes", record.getTargetMinutes(),
                            "policyVersion", record.getPolicyVersion(),
                            "nextDeadlineAt", String.valueOf(record.getNextDeadlineAt())));
        }
    }

    /**
     * The first public agent reply landed.
     *
     * <p>Runs in the same transaction as the message insert and the
     * {@code first_responded_at} write, which is what closes the reply-versus-breach race:
     * the poller's per-record {@code SELECT … FOR UPDATE} and this transaction contend for
     * the same row, so one of them goes second and sees the other's work.
     *
     * <p><b>{@code MET} even when the reply is late.</b> The clock stops at the moment of
     * the response either way; whether that was inside the target is decided by comparing
     * elapsed against it, in {@code SlaClockService.stop}. Marking it {@code MET}
     * unconditionally would be flattering and wrong.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public SlaOutcome markFirstResponseMet(Ticket ticket, OffsetDateTime at) {
        Optional<SlaRecord> found = active(ticket, SlaKind.FIRST_RESPONSE);
        if (found.isEmpty() || found.get().isTerminal()) {
            return null;
        }
        SlaRecord stopped = clocks.stop(found.get(), at);
        eventRecorder.record(ticket,
                stopped.getState() == SlaState.MET
                        ? TicketEventType.SLA_MET : TicketEventType.SLA_BREACHED,
                SlaKind.FIRST_RESPONSE.name(), stopped.getState().name());
        return new SlaOutcome(stopped.getState().name(),
                stopped.getState() == SlaState.MET ? stopped.getMetAt() : stopped.getBreachedAt());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> pauseResolution(Ticket ticket, String reason) {
        Optional<SlaRecord> found = active(ticket, SlaKind.RESOLUTION);
        if (found.isEmpty() || found.get().isTerminal()) {
            return Map.of();
        }
        OffsetDateTime now = clock.now();
        CalendarSpec calendar = calendars.current();
        long elapsedAtPause = calculator.elapsedBusinessMinutes(found.get().getId(), calendar, now);

        SlaRecord paused = clocks.pause(found.get(), pauseReasonOf(reason), now);
        eventRecorder.record(ticket, TicketEventType.SLA_PAUSED, SlaKind.RESOLUTION.name(),
                reason, Map.of("elapsedBusinessMinutesAtPause", elapsedAtPause));

        Map<String, Object> effect = new LinkedHashMap<>();
        effect.put("state", paused.getState().name());
        effect.put("pausedAt", now.toString());
        effect.put("reason", reason);
        effect.put("elapsedBusinessMinutesAtPause", elapsedAtPause);
        return Map.of("resolution", effect);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> resumeResolution(Ticket ticket) {
        Optional<SlaRecord> found = active(ticket, SlaKind.RESOLUTION);
        if (found.isEmpty() || found.get().isTerminal()) {
            return Map.of();
        }
        SlaRecord resumed = clocks.resume(found.get(), clock.now());
        eventRecorder.record(ticket, TicketEventType.SLA_RESUMED, SlaKind.RESOLUTION.name(),
                SlaState.RUNNING.name());

        return Map.of("resolution", Map.of(
                "state", resumed.getState().name(),
                "nextDeadlineAt", String.valueOf(resumed.getNextDeadlineAt())));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> stopResolution(Ticket ticket) {
        Optional<SlaRecord> found = active(ticket, SlaKind.RESOLUTION);
        if (found.isEmpty() || found.get().isTerminal()) {
            return Map.of();
        }
        OffsetDateTime now = clock.now();
        SlaRecord stopped = clocks.stop(found.get(), now);
        eventRecorder.record(ticket,
                stopped.getState() == SlaState.MET
                        ? TicketEventType.SLA_MET : TicketEventType.SLA_BREACHED,
                SlaKind.RESOLUTION.name(), stopped.getState().name());

        // Feeds the percentile that predicts the next ticket of this class. Written here,
        // in this transaction, with the figure the real arithmetic produced - see
        // V8__resolved_ticket_stat.sql for why it is not a materialized view.
        predictor.recordResolution(ticket,
                calculator.elapsedBusinessMinutes(stopped.getId(), calendars.current(), now),
                now);

        return Map.of("resolution", Map.of(
                "state", stopped.getState().name(),
                "metAt", String.valueOf(stopped.getMetAt()),
                "breachedAt", String.valueOf(stopped.getBreachedAt())));
    }

    /**
     * A reopened ticket gets a fresh resolution clock.
     *
     * <p>The old record is {@code CANCELLED} rather than reset, and it stays on the
     * ticket. {@code uq_sla_ticket_kind} excludes {@code CANCELLED}, which is precisely
     * what lets a second record exist — and the history is the point: "resolved in 4
     * hours, reopened, resolved again in 40" is a different and more useful story than
     * "resolved in 44 hours".
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void restartResolution(Ticket ticket) {
        OffsetDateTime now = clock.now();
        active(ticket, SlaKind.RESOLUTION).ifPresent(old -> clocks.cancel(old, now));

        if (!ticket.getPriority().isTriaged()) {
            return;
        }
        // Only the resolution clock restarts. A first response has already happened and
        // cannot happen again; restarting that clock would invent a second first reply.
        clocks.start(ticket, planTierOf(ticket), now).stream()
                .filter(r -> r.getKind() == SlaKind.RESOLUTION)
                .forEach(r -> eventRecorder.record(ticket, TicketEventType.SLA_STARTED,
                        "REOPENED", SlaKind.RESOLUTION.name()));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Optional<SlaRecord> active(Ticket ticket, SlaKind kind) {
        return records.findByTicketIdAndKindAndStateNot(ticket.getId(), kind,
                SlaState.CANCELLED);
    }

    /**
     * The tenant's plan tier, which is half of the policy key.
     *
     * <p>Read from the tenant rather than cached on the ticket: an upgrade from PRO to
     * ENTERPRISE should apply to the next ticket immediately, and the <i>target</i> that
     * results is snapshotted onto the record anyway, so nothing already in flight moves.
     */
    private PlanTier planTierOf(Ticket ticket) {
        return tenants.findById(ticket.getTenantId())
                .map(t -> t.getPlanTier())
                .orElse(PlanTier.FREE);
    }

    private static PauseReason pauseReasonOf(String reason) {
        try {
            return PauseReason.valueOf(reason);
        } catch (IllegalArgumentException e) {
            // Reached only from a status transition, where the reason is a TicketStatus
            // name and the two enums are kept aligned. A mismatch is a bug, not bad input,
            // so it is logged and defaulted rather than turned into a 4xx the agent
            // cannot act on.
            log.warn("Unmapped pause reason '{}'; recording WAITING_ON_CUSTOMER", reason);
            return PauseReason.WAITING_ON_CUSTOMER;
        }
    }
}
