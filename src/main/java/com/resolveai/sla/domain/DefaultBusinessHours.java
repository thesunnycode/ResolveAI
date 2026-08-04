package com.resolveai.sla.domain;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import org.springframework.stereotype.Component;

/**
 * Business-hours arithmetic, done on {@link LocalDateTime} and resolved to a zone last.
 *
 * <h2>Why the arithmetic is local and the zone comes last</h2>
 *
 * <p>Adding a {@link Duration} to a {@link ZonedDateTime} adds <i>physical</i> time. Across
 * a spring-forward that is not what anyone means: 09:00 plus eight working hours is 17:00
 * on the day the clocks change, exactly as on every other day, because the working day is
 * still eight hours long for the people working it. Doing every step on a
 * {@code LocalDateTime} in the calendar's zone and calling
 * {@link LocalDateTime#atZone} once at the end gets that right by construction.
 *
 * <p>The residual case is an instant that does not exist — 02:30 on the morning of a
 * spring-forward. {@code atZone} resolves it forward to 03:30, which is the only sensible
 * answer and is why the conversion happens through the zone rules rather than through an
 * offset held somewhere.
 *
 * <h2>The boundary convention</h2>
 *
 * <p>Half-open: {@code [dayStart, dayEnd)}. Both methods below implement it the same way,
 * and {@code BusinessHoursPropertyTest.roundTrip} is what proves they do.
 */
@Component
public class DefaultBusinessHours implements BusinessHours {

    /**
     * How far {@link #add} will search for a working day before giving up.
     *
     * <p>A calendar with at least one working day always finds one within seven, so any
     * search longer than this means the budget is being consumed at zero minutes a day and
     * the loop would never end. {@code CalendarSpec} already rejects an empty working-day
     * set; this is the guard for whatever the next bug is.
     */
    private static final int MAX_DAYS_SCANNED = 366 * 20;

    @Override
    public ZonedDateTime add(ZonedDateTime from, long businessMinutes, CalendarSpec calendar) {
        if (businessMinutes < 0) {
            throw new IllegalArgumentException(
                    "businessMinutes must not be negative: " + businessMinutes);
        }

        LocalDateTime cursor = from.withZoneSameInstant(calendar.zone()).toLocalDateTime();
        // The clock does not run at 03:00, so a budget measured from 03:00 starts at 09:00.
        cursor = advanceToWorkingTime(cursor, calendar);

        long remaining = businessMinutes;
        int daysScanned = 0;
        while (remaining > 0) {
            if (++daysScanned > MAX_DAYS_SCANNED) {
                throw new IllegalStateException("Could not consume " + businessMinutes
                        + " business minutes within " + MAX_DAYS_SCANNED
                        + " days for calendar " + calendar.zone());
            }

            LocalTime dayEnd = calendar.dayEnd();
            long availableToday = Duration.between(cursor.toLocalTime(), dayEnd).toMinutes();

            if (remaining < availableToday) {
                cursor = cursor.plusMinutes(remaining);
                remaining = 0;
            } else {
                // Consume the rest of today and move to the next working day's start.
                //
                // Note `<` rather than `<=`: when the budget exactly fills the day, the
                // result would be dayEnd, which the half-open convention says is not
                // working time. Landing there would break the inside-hours property and,
                // worse, would disagree with elapsedBusinessMinutes about whether that
                // minute had been spent. Rolling to the next day's start is the same
                // instant in business time and the one both methods agree on.
                remaining -= availableToday;
                cursor = startOfNextWorkingDay(cursor.toLocalDate(), calendar);
            }
        }
        return cursor.atZone(calendar.zone());
    }

    @Override
    public long elapsedBusinessMinutes(ZonedDateTime from, ZonedDateTime to,
                                       CalendarSpec calendar) {
        // Never negative. A negative elapsed figure would propagate into a remaining
        // budget and make a clock appear to run backwards.
        if (!to.isAfter(from)) {
            return 0;
        }

        LocalDateTime start = from.withZoneSameInstant(calendar.zone()).toLocalDateTime();
        LocalDateTime end = to.withZoneSameInstant(calendar.zone()).toLocalDateTime();

        long total = 0;
        for (LocalDate date = start.toLocalDate();
             !date.isAfter(end.toLocalDate());
             date = date.plusDays(1)) {

            if (!calendar.isWorkingDay(date)) {
                continue;
            }
            // Intersect [dayStart, dayEnd) with [start, end).
            LocalDateTime windowOpen = LocalDateTime.of(date, calendar.dayStart());
            LocalDateTime windowClose = LocalDateTime.of(date, calendar.dayEnd());
            LocalDateTime overlapStart = start.isAfter(windowOpen) ? start : windowOpen;
            LocalDateTime overlapEnd = end.isBefore(windowClose) ? end : windowClose;

            if (overlapEnd.isAfter(overlapStart)) {
                total += Duration.between(overlapStart, overlapEnd).toMinutes();
            }
        }
        return total;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * The first working instant at or after {@code local}.
     *
     * <p>Three cases, and the third is the one that is easy to get wrong: before the
     * working day (jump to {@code dayStart}), inside it (unchanged), and at or after
     * {@code dayEnd} — where the answer is the <i>next</i> working day, not this one.
     * Under the half-open convention {@code dayEnd} itself is already outside.
     */
    private static LocalDateTime advanceToWorkingTime(LocalDateTime local,
                                                      CalendarSpec calendar) {
        LocalDate date = local.toLocalDate();
        LocalTime time = local.toLocalTime();

        if (calendar.isWorkingDay(date)) {
            if (time.isBefore(calendar.dayStart())) {
                return LocalDateTime.of(date, calendar.dayStart());
            }
            if (time.isBefore(calendar.dayEnd())) {
                return local;
            }
        }
        return startOfNextWorkingDay(date, calendar);
    }

    private static LocalDateTime startOfNextWorkingDay(LocalDate after, CalendarSpec calendar) {
        LocalDate date = after.plusDays(1);
        for (int scanned = 0; scanned < MAX_DAYS_SCANNED; scanned++) {
            if (calendar.isWorkingDay(date)) {
                return LocalDateTime.of(date, calendar.dayStart());
            }
            date = date.plusDays(1);
        }
        throw new IllegalStateException(
                "No working day found within " + MAX_DAYS_SCANNED + " days after " + after);
    }
}
