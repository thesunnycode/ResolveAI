package com.resolveai.sla.service;

import com.resolveai.sla.domain.CalendarSpec;
import com.resolveai.sla.domain.SlaEscalation;
import com.resolveai.sla.domain.SlaPolicy;
import com.resolveai.sla.domain.SlaRecord;
import com.resolveai.sla.repository.SlaEscalationRepository;
import com.resolveai.sla.repository.SlaPolicyRepository;
import com.resolveai.sla.repository.SlaRecordRepository;
import com.resolveai.ticketing.domain.Ticket;
import com.resolveai.ticketing.domain.TicketEventType;
import com.resolveai.ticketing.service.TicketEventRecorder;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The escalation ladder: what happens when a clock crosses a rung.
 *
 * <h2>The exactly-once mechanism</h2>
 *
 * <p>The poller is <b>at-least-once by construction</b> — it claims ids in one short
 * transaction and processes them in others, so two runs can pick the same record. That is
 * a deliberate simplification, and it is only safe because of one line of DDL:
 * {@code uq_escalation_rung UNIQUE (sla_record_id, rung)}.
 *
 * <p>So the escalation row is inserted <b>first</b>, before the notification. If it
 * violates the constraint, this rung has already fired and the method returns having sent
 * nothing. An at-least-once trigger becomes an exactly-once effect with no distributed
 * lock, no leader election and no coordination between instances.
 *
 * <p><b>The constraint name is checked before the violation is swallowed.</b> A bare
 * {@code catch (DataIntegrityViolationException)} would also hide a genuine bug in the
 * notification insert — a null recipient, a bad enum — and that bug would then never
 * surface anywhere, because the poller would treat it as "already fired" for ever.
 */
@Service
public class EscalationService {

    private static final Logger log = LoggerFactory.getLogger(EscalationService.class);

    private static final String RUNG_CONSTRAINT = "uq_escalation_rung";

    private final SlaRecordRepository records;
    private final SlaEscalationRepository escalations;
    private final SlaPolicyRepository policies;
    private final SlaCalculator calculator;
    private final CalendarService calendars;
    private final SlaClockService clocks;
    private final TicketEventRecorder eventRecorder;
    private final JdbcTemplate jdbc;

    public EscalationService(SlaRecordRepository records, SlaEscalationRepository escalations,
                             SlaPolicyRepository policies, SlaCalculator calculator,
                             CalendarService calendars, SlaClockService clocks,
                             TicketEventRecorder eventRecorder, JdbcTemplate jdbc) {
        this.records = records;
        this.escalations = escalations;
        this.policies = policies;
        this.calculator = calculator;
        this.calendars = calendars;
        this.clocks = clocks;
        this.eventRecorder = eventRecorder;
        this.jdbc = jdbc;
    }

    /** What a rung did, for logging and for the poller's metrics. */
    public enum Outcome {
        FIRED,
        /** Already fired — the constraint did its job. Not an error. */
        ALREADY_FIRED,
        /** Nothing was due after all: the deadline moved, or the clock stopped. */
        NOT_DUE
    }

    /**
     * Processes one due record. Called inside the poller's per-record transaction, with
     * the row already locked and the tenant context already set.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome processRung(SlaRecord record, OffsetDateTime now) {
        Short rung = record.getNextRung();
        if (rung == null || record.isTerminal()) {
            return Outcome.NOT_DUE;
        }

        CalendarSpec calendar = calendars.current();
        long elapsed = calculator.elapsedBusinessMinutes(record.getId(), calendar, now);
        int pct = record.getTargetMinutes() == 0 ? 100
                : (int) (elapsed * 100 / record.getTargetMinutes());

        // The record was claimed because its deadline passed, but the deadline is an
        // estimate made in advance and the elapsed figure is the truth. If a pause landed
        // between the two, the rung is not actually crossed yet.
        if (pct < rung) {
            log.debug("SLA record {} is at {}% of target, below rung {}; rescheduling",
                    record.getId(), pct, rung);
            record.setNextDeadlineAt(clocks.deadlineForRemaining(now,
                    record.getTargetMinutes(), rung, elapsed, calendar));
            records.saveAndFlush(record);
            return Outcome.NOT_DUE;
        }

        // ── The guard. Inserted before anything is sent. ─────────────────────
        try {
            escalations.saveAndFlush(new SlaEscalation(record.getId(), rung, (int) elapsed, null));
        } catch (DataIntegrityViolationException e) {
            if (!isConstraint(e, RUNG_CONSTRAINT)) {
                // Not the guard doing its job — a real problem. Rethrowing matters: a
                // blanket catch here would hide it for ever behind "already fired".
                throw e;
            }
            log.debug("Rung {} already fired for SLA record {}", rung, record.getId());
            return Outcome.ALREADY_FIRED;
        }

        Long recipient = recipientFor(record, rung);
        if (recipient != null) {
            // Linked afterwards, which V9 narrowed the append-only trigger to allow: the
            // notification cannot exist before the row that guards it, and a fired rung
            // with no record of what it sent is the first thing anyone asks about when an
            // alert is disputed.
            Long notificationId = notify(record, rung, elapsed, recipient);
            jdbc.update("UPDATE sla_escalation SET notification_id = ? "
                        + "WHERE sla_record_id = ? AND rung = ?",
                    notificationId, record.getId(), (int) rung);
        }

        advance(record, rung, elapsed, calendar, now);
        return Outcome.FIRED;
    }

    /**
     * Moves the record to the next rung, or breaches it at 100.
     *
     * <p>A breached clock stops: {@code next_deadline_at} becomes null, which takes it out
     * of {@code idx_sla_poller} entirely. Leaving it in would have the poller pick it up
     * every ten seconds for ever, finding nothing to do each time.
     */
    private void advance(SlaRecord record, short rung, long elapsed, CalendarSpec calendar,
                         OffsetDateTime now) {
        Ticket ticket = record.getTicket();

        if (rung >= 100) {
            record.breach(now);
            records.saveAndFlush(record);
            eventRecorder.record(ticket, TicketEventType.SLA_BREACHED,
                    record.getKind().name(), "BREACHED",
                    Map.of("elapsedBusinessMinutes", elapsed,
                            "targetMinutes", record.getTargetMinutes()));
            log.warn("SLA BREACHED: ticket {} {} at {} business minutes against a target of {}",
                    ticket.getReference(), record.getKind(), elapsed, record.getTargetMinutes());
            return;
        }

        Short next = nextRungAfter(record, rung);
        record.setNextRung(next);
        // From the remaining budget, not from the rung's full share: this clock has
        // already spent `elapsed` minutes and the next rung is that much closer.
        record.setNextDeadlineAt(clocks.deadlineForRemaining(now, record.getTargetMinutes(),
                next == null ? 100 : next, elapsed, calendar));
        records.saveAndFlush(record);

        eventRecorder.record(ticket, TicketEventType.SLA_ESCALATED,
                String.valueOf(rung), String.valueOf(next),
                Map.of("elapsedBusinessMinutes", elapsed, "rung", rung));
    }

    /**
     * The next rung in the policy after this one.
     *
     * <p>Read from the policy rather than hard-coded 50/75/90/100: the rungs are per
     * policy, so an enterprise P1 can warn at 25% and a free P4 need not be escalated four
     * times on its way to a breach nobody is paid to prevent.
     *
     * <p>Falls back to 100 if the policy has been superseded and no longer resolves — a
     * clock must always have a breach rung, or it would run past its target in silence.
     */
    private Short nextRungAfter(SlaRecord record, short current) {
        List<Short> rungs = Optional.ofNullable(
                        policies.findById(record.getSlaPolicyId()).orElse(null))
                .map(SlaPolicy::rungs)
                .orElse(List.of((short) 50, (short) 75, (short) 90, (short) 100));
        return rungs.stream().filter(r -> r > current).findFirst().orElse((short) 100);
    }

    /**
     * Who hears about it, by rung.
     *
     * <p>50% goes to the assignee, 75% and above to the team lead. Escalating to a human
     * at 50% and to their lead at 75% is the point of a ladder: by the time it breaches,
     * two people have already been told and had a chance to act.
     *
     * <p><b>Each rung falls back to the other person rather than to nobody.</b> An
     * unassigned ticket at 50% goes to the lead - it is the lead's queue that is short an
     * owner, and that is exactly when someone needs to know. A team with no lead keeps the
     * assignee on the upper rungs. Only a ticket with neither is left unaddressed.
     */
    private Long recipientFor(SlaRecord record, short rung) {
        Ticket ticket = record.getTicket();
        Long assignee = ticket.getAssignee() == null ? null : ticket.getAssignee().getId();
        Long lead = teamLeadOf(ticket);
        Long recipient = rung < 75
                ? (assignee != null ? assignee : lead)
                : (lead != null ? lead : assignee);
        if (recipient == null) {
            // The escalation row is still written - it is the notification that has no
            // addressee. The at-risk queue is what surfaces these.
            log.warn("SLA rung {} on ticket {} with no assignee and no team lead; "
                     + "no notification sent", rung, ticket.getReference());
        }
        return recipient;
    }

    /**
     * The active lead of the ticket's team, lowest id first so the choice is stable.
     *
     * <p>Native, with the tenant spelled out: this runs on the poller thread, and a
     * native query is not covered by {@code @TenantId}.
     */
    private Long teamLeadOf(Ticket ticket) {
        if (ticket.getTeam() == null) {
            return null;
        }
        List<Long> leads = jdbc.queryForList("""
                SELECT id FROM app_user
                 WHERE tenant_id = ? AND team_id = ? AND role = 'TEAM_LEAD' AND is_active
                 ORDER BY id
                 LIMIT 1
                """, Long.class, ticket.getTenantId(), ticket.getTeam().getId());
        return leads.isEmpty() ? null : leads.get(0);
    }

    /** @return the id of the notification written */
    private Long notify(SlaRecord record, short rung, long elapsed, Long recipient) {
        Ticket ticket = record.getTicket();
        String kind = rung >= 100 ? "SLA_BREACH" : "SLA_ESCALATION";
        String title = rung >= 100
                ? "SLA breached: " + ticket.getReference()
                : "SLA at " + rung + "%: " + ticket.getReference();

        return jdbc.queryForObject("""
                INSERT INTO notification (tenant_id, recipient_id, kind, title, body, link_url)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class,
                record.getTenantId(), recipient, kind, title,
                record.getKind() + " is at " + elapsed + " of " + record.getTargetMinutes()
                        + " business minutes.",
                "/tickets/" + ticket.getId());
    }

    /**
     * Whether this violation came from the named constraint.
     *
     * <p>Walks the cause chain and matches on the message, because the constraint name is
     * not exposed as a field on the Spring exception. Crude, and deliberately narrow: the
     * alternative is swallowing every integrity violation, which would hide a real bug for
     * ever behind "already fired".
     */
    static boolean isConstraint(Throwable e, String constraintName) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains(constraintName)) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
