package com.resolveai.sla.service;

import com.resolveai.sla.domain.CalendarSpec;
import com.resolveai.sla.domain.SlaClockSegment;
import com.resolveai.sla.domain.SlaEscalation;
import com.resolveai.sla.domain.SlaRecord;
import com.resolveai.sla.domain.SlaState;
import com.resolveai.sla.repository.SlaClockSegmentRepository;
import com.resolveai.sla.repository.SlaEscalationRepository;
import com.resolveai.sla.repository.SlaRecordRepository;
import com.resolveai.sla.web.dto.SlaResponse;
import com.resolveai.ticketing.service.SlaSummaryProvider;
import com.resolveai.ticketing.web.dto.SlaSummary;
import com.resolveai.platform.time.DatabaseClock;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rendering clocks for the API. The read half of the SLA port.
 *
 * <p>Registering this bean replaces {@code SlaSummaryProvider.NoOp} the same way
 * {@link SlaLifecycleService} replaces the no-op lifecycle, so the ticket list and detail
 * responses start carrying real SLA data without a change in {@code TicketMapper}.
 */
@Service
public class SlaReadService implements SlaSummaryProvider {

    private final SlaRecordRepository records;
    private final SlaClockSegmentRepository segments;
    private final SlaEscalationRepository escalations;
    private final SlaCalculator calculator;
    private final CalendarService calendars;
    private final BreachPredictor predictor;
    private final DatabaseClock clock;

    public SlaReadService(SlaRecordRepository records, SlaClockSegmentRepository segments,
                          SlaEscalationRepository escalations, SlaCalculator calculator,
                          CalendarService calendars, BreachPredictor predictor,
                          DatabaseClock clock) {
        this.records = records;
        this.segments = segments;
        this.escalations = escalations;
        this.calculator = calculator;
        this.calendars = calendars;
        this.predictor = predictor;
        this.clock = clock;
    }

    /**
     * The compact form for a queue row.
     *
     * <p>No segments, no escalations, no prediction. A page of twenty-five tickets each
     * carrying a full segment history is a few hundred rows of JSON to colour a table,
     * and the prediction alone would be twenty-five percentile queries.
     */
    @Override
    @Transactional(readOnly = true)
    public SlaSummary summaryFor(Long ticketId) {
        List<SlaRecord> all = activeRecords(ticketId);
        if (all.isEmpty()) {
            return null;
        }
        CalendarSpec calendar = calendars.current();
        OffsetDateTime now = clock.now();

        SlaSummary.Clock first = null;
        SlaSummary.Clock resolution = null;
        for (SlaRecord record : all) {
            SlaSummary.Clock clock = new SlaSummary.Clock(record.getState().name(),
                    remaining(record, calendar, now),
                    record.getState() == SlaState.RUNNING
                            && predictor.isAtRisk(record, calendar, now));
            switch (record.getKind()) {
                case FIRST_RESPONSE -> first = clock;
                case RESOLUTION -> resolution = clock;
            }
        }
        return new SlaSummary(first, resolution);
    }

    /** The full view: both clocks, every segment, every escalation, the calendar. */
    @Override
    @Transactional(readOnly = true)
    public SlaResponse detailFor(Long ticketId) {
        List<SlaRecord> all = activeRecords(ticketId);
        if (all.isEmpty()) {
            return null;
        }
        CalendarSpec calendar = calendars.current();
        OffsetDateTime now = clock.now();

        return new SlaResponse(ticketId, calendarView(calendar),
                all.stream().map(r -> clockView(r, calendar, now)).toList());
    }

    private SlaResponse.ClockView clockView(SlaRecord record, CalendarSpec calendar,
                                            OffsetDateTime now) {
        List<SlaClockSegment> all = segments.findBySlaRecordIdOrderByStartedAtAsc(
                record.getId());
        long elapsed = calculator.elapsedOver(all, calendar, now);

        List<SlaResponse.SegmentView> segmentViews = all.stream()
                .map(s -> new SlaResponse.SegmentView(
                        s.getState().name(),
                        s.getStartedAt().toString(),
                        s.getEndedAt() == null ? null : s.getEndedAt().toString(),
                        calculator.businessMinutesOf(s, calendar, now),
                        s.getPauseReason() == null ? null : s.getPauseReason().name()))
                .toList();

        List<SlaResponse.EscalationView> fired =
                escalations.findBySlaRecordIdOrderByRungAsc(record.getId()).stream()
                        .map(SlaReadService::escalationView)
                        .toList();

        return new SlaResponse.ClockView(
                record.getKind().name(), record.getState().name(), record.getPolicyVersion(),
                record.getTargetMinutes(), elapsed, record.getTargetMinutes() - elapsed,
                record.getMetAt() == null ? null : record.getMetAt().toString(),
                record.getBreachedAt() == null ? null : record.getBreachedAt().toString(),
                record.getNextDeadlineAt() == null ? null : record.getNextDeadlineAt().toString(),
                record.getNextRung(), segmentViews, fired,
                predictor.predict(record, calendar, now).orElse(null));
    }

    private static SlaResponse.EscalationView escalationView(SlaEscalation e) {
        return new SlaResponse.EscalationView(e.getRung(), e.getFiredAt().toString(),
                e.getElapsedMinutesAtFire());
    }

    private static SlaResponse.CalendarView calendarView(CalendarSpec calendar) {
        return new SlaResponse.CalendarView(
                calendar.zone().getId(),
                calendar.workingDays().stream().map(DayOfWeek::getValue).sorted().toList(),
                calendar.dayStart().toString(), calendar.dayEnd().toString());
    }

    /**
     * Remaining budget, which <b>goes negative once the target is passed</b> rather than
     * clamping at zero. "Twelve minutes over" and "just on time" are different situations,
     * and a queue should be able to show which one it is looking at.
     */
    private long remaining(SlaRecord record, CalendarSpec calendar, OffsetDateTime now) {
        return record.getTargetMinutes()
                - calculator.elapsedBusinessMinutes(record.getId(), calendar, now);
    }

    private List<SlaRecord> activeRecords(Long ticketId) {
        return records.findByTicketId(ticketId).stream()
                .filter(r -> r.getState() != SlaState.CANCELLED)
                .toList();
    }
}
