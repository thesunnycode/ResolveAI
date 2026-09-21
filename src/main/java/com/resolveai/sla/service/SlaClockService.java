package com.resolveai.sla.service;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.iam.domain.PlanTier;
import com.resolveai.sla.domain.BusinessHours;
import com.resolveai.sla.domain.CalendarSpec;
import com.resolveai.sla.domain.PauseReason;
import com.resolveai.sla.domain.SegmentState;
import com.resolveai.sla.domain.SlaClockSegment;
import com.resolveai.sla.domain.SlaKind;
import com.resolveai.sla.domain.SlaPolicy;
import com.resolveai.sla.domain.SlaRecord;
import com.resolveai.sla.domain.SlaState;
import com.resolveai.sla.repository.SlaClockSegmentRepository;
import com.resolveai.sla.repository.SlaRecordRepository;
import com.resolveai.ticketing.domain.Ticket;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starting, pausing, resuming and stopping clocks.
 *
 * <p>Every method here runs in the caller's transaction ({@code MANDATORY}). A status
 * change that commits while its clock pause rolls back leaves a permanently wrong SLA and
 * nothing anywhere reports it, so the two must share a fate.
 */
@Service
public class SlaClockService {

    private static final Logger log = LoggerFactory.getLogger(SlaClockService.class);

    private final SlaRecordRepository records;
    private final SlaClockSegmentRepository segments;
    private final SlaPolicyResolver policyResolver;
    private final SlaCalculator calculator;
    private final CalendarService calendars;
    private final BusinessHours businessHours;

    public SlaClockService(SlaRecordRepository records, SlaClockSegmentRepository segments,
                           SlaPolicyResolver policyResolver, SlaCalculator calculator,
                           CalendarService calendars, BusinessHours businessHours) {
        this.records = records;
        this.segments = segments;
        this.policyResolver = policyResolver;
        this.calculator = calculator;
        this.calendars = calendars;
        this.businessHours = businessHours;
    }

    // ── Task 24: start ──────────────────────────────────────────────────────

    /**
     * Creates both clocks for a ticket whose priority is known.
     *
     * <p>Two records, two open {@code RUNNING} segments, two absolute deadlines computed
     * in business hours — and the policy's targets and version label <b>snapshotted</b>
     * onto each record, so a policy change in October cannot retroactively breach a ticket
     * from September.
     *
     * <p>The first deadline is not the target: it is the <i>first rung</i>, typically 50%.
     * The poller wakes at each rung rather than only at the breach, which is what makes an
     * escalation ladder possible with one indexed column and no scheduler.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<SlaRecord> start(Ticket ticket, PlanTier planTier, OffsetDateTime now) {
        if (!ticket.getPriority().isTriaged()) {
            // Not an error. A ticket with no priority has no policy and therefore no
            // clock; Phase 6's triage worker calls this again once it has decided.
            log.debug("Ticket {} is untriaged; no SLA started", ticket.getId());
            return List.of();
        }

        // Per kind, not "does this ticket have any clock at all". A reopened ticket keeps
        // its terminal FIRST_RESPONSE record - a first reply cannot happen twice - and a
        // whole-ticket check would read that as "clocks already exist" and quietly give
        // the reopened ticket no resolution clock at all. Nothing would report it; the
        // ticket would simply never breach again.
        List<SlaRecord> existing = records.findByTicketId(ticket.getId()).stream()
                .filter(r -> r.getState() != SlaState.CANCELLED)
                .toList();
        Set<SlaKind> covered = existing.stream().map(SlaRecord::getKind)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(SlaKind.class)));
        if (covered.size() == SlaKind.values().length) {
            // Re-entry. uq_sla_ticket_kind would raise anyway; checking first turns a
            // constraint violation into an ordinary idempotent return.
            return existing;
        }

        SlaPolicy policy = policyResolver.resolve(ticket.getPriority(), planTier, now);
        CalendarSpec calendar = calendars.current();

        List<SlaRecord> created = new ArrayList<>(existing);
        for (SlaKind kind : SlaKind.values()) {
            if (!covered.contains(kind)) {
                created.add(startOne(ticket, policy, kind, calendar, now));
            }
        }
        return created;
    }

    private SlaRecord startOne(Ticket ticket, SlaPolicy policy, SlaKind kind,
                               CalendarSpec calendar, OffsetDateTime now) {
        int target = policy.targetMinutesFor(kind);
        short firstRung = policy.rungs().isEmpty() ? 100 : policy.rungs().get(0);
        OffsetDateTime deadline = deadlineFor(now, target, firstRung, calendar);

        SlaRecord record = new SlaRecord(ticket, policy, kind, deadline);
        try {
            records.saveAndFlush(record);
        } catch (DataIntegrityViolationException e) {
            // Two concurrent triage attempts. uq_sla_ticket_kind means one of them lost,
            // and the desired end state - a clock exists - is already true.
            log.debug("SLA {} already exists for ticket {}", kind, ticket.getId());
            return records.findByTicketIdAndKindAndStateNot(ticket.getId(), kind,
                            SlaState.CANCELLED)
                    .orElseThrow(() -> e);
        }
        segments.save(new SlaClockSegment(record.getId(), SegmentState.RUNNING, now, null));
        return record;
    }

    // ── Task 25: pause ──────────────────────────────────────────────────────

    /**
     * Pauses a clock. <b>The order of these statements is the whole task.</b>
     *
     * <p>Close the open segment with a conditional UPDATE, check the affected-row count,
     * then insert the paused segment, then move the record. Written that way for two
     * reasons:
     *
     * <ol>
     *   <li><b>The row count is the concurrency answer.</b> Two requests pausing the same
     *       clock both arrive here; one closes the segment and gets {@code 1}, the other
     *       gets {@code 0} and returns the current state as an idempotent {@code 200}.
     *       Pausing an already-paused clock is not an error — the caller wanted it paused
     *       and it is.
     *   <li><b>Hibernate does not flush in source order.</b> Doing this through a
     *       cascading {@code @OneToMany} lets it order the INSERT before the UPDATE, which
     *       trips {@code uq_segment_open} — and the violation names the constraint and
     *       points at the insert, while the actual mistake is that the close had not
     *       happened yet. Explicit statements cannot be reordered.
     * </ol>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SlaRecord pause(SlaRecord record, PauseReason reason, OffsetDateTime now) {
        if (record.isTerminal()) {
            throw new ApiException(ErrorCode.SLA_ALREADY_TERMINAL,
                    "The " + record.getKind() + " clock is already " + record.getState()
                    + " and cannot be paused.");
        }

        if (record.getState() == SlaState.PAUSED) {
            // Already paused, and long enough ago to have committed. Without this the
            // conditional close below would find the *paused* segment open, close it and
            // append a third - churning the history and moving the pause's start time,
            // so the elapsed arithmetic would quietly change every time somebody clicked
            // pause twice. The row-count guard cannot catch this case: there genuinely is
            // an open segment to close.
            return record;
        }

        int closed = segments.closeOpenSegment(record.getId(), now);
        if (closed == 0) {
            // The concurrent case: both requests saw RUNNING, one of them closed the
            // segment first, and this one is the loser. The caller wanted it paused and
            // it is, so this is an idempotent 200 rather than an error.
            return record;
        }

        segments.save(new SlaClockSegment(record.getId(), SegmentState.PAUSED, now, reason));
        record.pause();
        records.saveAndFlush(record);
        return record;
    }

    /**
     * Resumes a clock, recomputing the deadline <b>from the elapsed total</b>.
     *
     * <p>Not from the original start. A ticket that spent two days waiting on the customer
     * has burned none of its budget, so its new deadline is "remaining budget, measured
     * from now" — recomputing from the start would hand back the paused time as though it
     * had been spent, which is the entire thing pausing exists to prevent.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SlaRecord resume(SlaRecord record, OffsetDateTime now) {
        if (record.isTerminal()) {
            throw new ApiException(ErrorCode.SLA_ALREADY_TERMINAL,
                    "The " + record.getKind() + " clock is " + record.getState()
                    + " and cannot be resumed.");
        }
        if (record.getState() == SlaState.RUNNING) {
            return record;
        }

        int closed = segments.closeOpenSegment(record.getId(), now);
        if (closed == 0) {
            log.warn("Resuming SLA record {} that had no open segment; opening one", record.getId());
        }
        segments.save(new SlaClockSegment(record.getId(), SegmentState.RUNNING, now, null));

        CalendarSpec calendar = calendars.current();
        long elapsed = calculator.elapsedBusinessMinutes(record.getId(), calendar, now);
        short rung = record.getNextRung() == null ? 100 : record.getNextRung();
        long rungBudget = (long) record.getTargetMinutes() * rung / 100;
        // Clamped at zero: a clock resumed after its rung budget is already spent is due
        // immediately, not in the past. A negative argument would throw, and a deadline in
        // the past is exactly what the poller is built to pick up on its next run.
        long remaining = Math.max(0, rungBudget - elapsed);

        record.resume(businessHours.add(now.atZoneSameInstant(calendar.zone()), remaining,
                calendar).toOffsetDateTime());
        records.saveAndFlush(record);
        return record;
    }

    // ── Terminal transitions ────────────────────────────────────────────────

    /**
     * Stops a clock and judges it.
     *
     * <p>{@code MET} or {@code BREACHED} by comparing elapsed against the snapshotted
     * target — not by looking at whether a breach escalation happened to have fired. The
     * poller is at-least-once and may not have run yet; the arithmetic is the authority.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SlaRecord stop(SlaRecord record, OffsetDateTime now) {
        if (record.isTerminal()) {
            return record;
        }

        CalendarSpec calendar = calendars.current();
        segments.closeOpenSegment(record.getId(), now);
        long elapsed = calculator.elapsedBusinessMinutes(record.getId(), calendar, now);

        if (elapsed > record.getTargetMinutes()) {
            record.breach(now);
        } else {
            record.meet(now);
        }
        records.saveAndFlush(record);
        return record;
    }

    /** Cancels a clock so a replacement can be created — reopening a resolved ticket. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancel(SlaRecord record, OffsetDateTime now) {
        segments.closeOpenSegment(record.getId(), now);
        record.cancel();
        records.saveAndFlush(record);
    }

    // -- Task 27: retarget after a human priority override --------------------

    /**
     * Re-points every live clock on a ticket at the policy for its new priority.
     *
     * <p>Called after a human override, and only then. Three things have to be true at
     * once for this to be correct, and each of them is a way of getting it wrong:
     *
     * <ol>
     *   <li><b>Elapsed carries over.</b> The records keep their segments, so a ticket
     *       ninety minutes into a P3 clock is ninety minutes into its new P1 clock.
     *       Starting fresh would hand back the time already spent and make every
     *       escalation-to-P1 look like an improvement in SLA performance.
     *   <li><b>The new deadline is computed from the remaining budget</b>, via
     *       {@link #deadlineForRemaining}, not from the full new target. A P1 target of
     *       sixty minutes on a clock that has already run ninety is <i>overdue</i>, and
     *       the poller must see it that way on its very next pass.
     *   <li><b>Terminal records are left alone.</b> A first-response clock that was met
     *       yesterday stays met. Retargeting it would rewrite history and could flip a
     *       met promise to a breached one long after the fact.
     * </ol>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<SlaRecord> retarget(Ticket ticket, PlanTier planTier, OffsetDateTime now) {
        if (!ticket.getPriority().isTriaged()) {
            return List.of();
        }

        SlaPolicy policy = policyResolver.resolve(ticket.getPriority(), planTier, now);
        CalendarSpec calendar = calendars.current();
        List<SlaRecord> changed = new ArrayList<>(2);

        for (SlaRecord record : records.findByTicketId(ticket.getId())) {
            if (record.isTerminal() || record.getState() == SlaState.CANCELLED) {
                continue;
            }
            int newTarget = policy.targetMinutesFor(record.getKind());
            long elapsed = calculator.elapsedBusinessMinutes(record.getId(), calendar, now);
            short rung = record.getNextRung() == null ? 100 : record.getNextRung();

            record.retarget(policy.getId(), policy.getVersionLabel(), newTarget,
                    deadlineForRemaining(now, newTarget, rung, elapsed, calendar));
            records.saveAndFlush(record);
            changed.add(record);
            log.info("SLA {} on ticket {} retargeted to {} minutes ({}), next deadline {}",
                    record.getKind(), ticket.getId(), newTarget, policy.getVersionLabel(),
                    record.getNextDeadlineAt());
        }
        return changed;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** When the clock will next need attention: the rung's share of the target. */
    public OffsetDateTime deadlineFor(OffsetDateTime from, int targetMinutes, short rung,
                                      CalendarSpec calendar) {
        long budget = (long) targetMinutes * rung / 100;
        return businessHours.add(from.atZoneSameInstant(calendar.zone()), budget, calendar)
                .toOffsetDateTime();
    }

    /**
     * The same, for a clock that has already burned {@code elapsed} minutes.
     *
     * <p><b>The elapsed argument is the whole point.</b> Scheduling the next rung as
     * "now plus the rung's full budget" ignores everything the clock has already spent,
     * so a record that fires its 50% rung when it is in fact 90% used would then not be
     * looked at again until another 75% of the target had passed — long after the breach.
     * The ladder would silently stop climbing exactly on the tickets it exists for.
     *
     * <p>Clamped at zero: a rung already overdue is due now, not in the past. A negative
     * argument would throw, and a deadline of {@code now} is picked up by the very next
     * poll, which is how a poller that fires one rung per pass walks a badly overdue
     * clock up the whole ladder.
     */
    public OffsetDateTime deadlineForRemaining(OffsetDateTime from, int targetMinutes,
                                               short rung, long elapsedMinutes,
                                               CalendarSpec calendar) {
        long budget = (long) targetMinutes * rung / 100;
        long remaining = Math.max(0, budget - elapsedMinutes);
        return businessHours.add(from.atZoneSameInstant(calendar.zone()), remaining, calendar)
                .toOffsetDateTime();
    }

    public Optional<SlaRecord> activeRecord(Long ticketId, SlaKind kind) {
        return records.findByTicketIdAndKindAndStateNot(ticketId, kind, SlaState.CANCELLED);
    }
}
