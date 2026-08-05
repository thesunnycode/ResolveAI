package com.resolveai.sla.service;

import com.resolveai.sla.domain.BusinessHours;
import com.resolveai.sla.domain.CalendarSpec;
import com.resolveai.sla.domain.SlaClockSegment;
import com.resolveai.sla.repository.SlaClockSegmentRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How much of a clock's budget has been spent.
 *
 * <h2>There is no {@code elapsed_minutes} column anywhere, and there will not be</h2>
 *
 * <p>Elapsed time is derived: sum {@link BusinessHours#elapsedBusinessMinutes} over the
 * {@code RUNNING} segments, treating the open one as running until now.
 *
 * <p>A stored counter is tempting — this is a sum on every read — and it is a lost update
 * waiting to happen. The counter would be written by the pause path, the resume path and
 * the poller, at least two of which can overlap; the loser's increment vanishes and the
 * clock is quietly wrong for ever, with no error and nothing to compare against. Derived
 * from append-only rows, the number cannot be wrong, only slow.
 *
 * <p>If it ever <i>is</i> too slow, the answer is a cached read model rebuilt from the
 * segments — not a mutable counter. The distinction matters: a cache that drifts can be
 * rebuilt from the truth, and a counter that drifts <i>is</i> the truth.
 *
 * <p>In practice a clock has two to six segments. The sum is not the bottleneck; the
 * per-row {@code BusinessHours} walk over a 90-day history is, which is why
 * {@code resolved_ticket_stats} exists as a materialized view for the prediction path.
 */
@Service
public class SlaCalculator {

    private final SlaClockSegmentRepository segments;
    private final BusinessHours businessHours;

    public SlaCalculator(SlaClockSegmentRepository segments, BusinessHours businessHours) {
        this.segments = segments;
        this.businessHours = businessHours;
    }

    @Transactional(readOnly = true)
    public long elapsedBusinessMinutes(Long recordId, CalendarSpec calendar,
                                       OffsetDateTime now) {
        return elapsedOver(segments.findBySlaRecordIdOrderByStartedAtAsc(recordId),
                calendar, now);
    }

    /**
     * The same sum over segments already in hand, so the detail endpoint can render every
     * segment's contribution without re-querying once per segment.
     */
    public long elapsedOver(List<SlaClockSegment> all, CalendarSpec calendar,
                            OffsetDateTime now) {
        long total = 0;
        for (SlaClockSegment segment : all) {
            total += businessMinutesOf(segment, calendar, now);
        }
        return total;
    }

    /**
     * One segment's contribution.
     *
     * <p>A {@code PAUSED} segment contributes zero by definition — that is what pausing
     * means — and the open segment is measured up to {@code now}, which is why a running
     * clock's elapsed figure changes between two reads a minute apart without anything
     * being written.
     */
    public long businessMinutesOf(SlaClockSegment segment, CalendarSpec calendar,
                                  OffsetDateTime now) {
        if (!segment.isRunning()) {
            return 0;
        }
        OffsetDateTime end = segment.getEndedAt() == null ? now : segment.getEndedAt();
        return businessHours.elapsedBusinessMinutes(
                segment.getStartedAt().atZoneSameInstant(calendar.zone()),
                end.atZoneSameInstant(calendar.zone()),
                calendar);
    }
}
