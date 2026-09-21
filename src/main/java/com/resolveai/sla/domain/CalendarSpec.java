package com.resolveai.sla.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Set;

/**
 * A tenant's working hours, as a value.
 *
 * <p>Separate from the {@code BusinessCalendar} entity on purpose: the arithmetic in
 * {@link BusinessHours} is pure, and giving it a JPA entity would make every unit test of
 * it need a database. This record is what the entity is converted <i>to</i>.
 *
 * @param zone        an IANA zone, never a fixed offset. {@code Asia/Kolkata}, not
 *                    {@code +05:30} — an offset does not know about daylight saving, so a
 *                    New York calendar stored as {@code -05:00} silently runs an hour late
 *                    for eight months of the year.
 * @param workingDays which days the clock runs on. Not necessarily Monday to Friday:
 *                    {@code Asia/Dubai} support desks commonly run Sunday to Thursday, and
 *                    an implementation that hard-codes the weekend is wrong for them in a
 *                    way that only shows up as SLA figures that are quietly two days out.
 * @param dayStart    inclusive
 * @param dayEnd      <b>exclusive.</b> See {@link BusinessHours} for why the convention has
 *                    to be stated and why both methods must share it.
 * @param holidays    dates, not instants. A holiday is a calendar day in the tenant's zone.
 */
public record CalendarSpec(
        ZoneId zone,
        Set<DayOfWeek> workingDays,
        LocalTime dayStart,
        LocalTime dayEnd,
        Set<LocalDate> holidays) {

    public CalendarSpec {
        if (workingDays == null || workingDays.isEmpty()) {
            // Guarding here rather than in the loop that would spin forever. A calendar
            // with no working days means the clock can never advance, so add() would
            // search for a working day it will never find.
            throw new IllegalArgumentException("A calendar must have at least one working day");
        }
        if (!dayEnd.isAfter(dayStart)) {
            throw new IllegalArgumentException(
                    "dayEnd must be after dayStart (got " + dayStart + " to " + dayEnd + ")");
        }
        workingDays = EnumSet.copyOf(workingDays);
        holidays = holidays == null ? Set.of() : Set.copyOf(holidays);
    }

    /** Minutes in one full working day. */
    public long minutesPerWorkingDay() {
        return java.time.Duration.between(dayStart, dayEnd).toMinutes();
    }

    public boolean isWorkingDay(LocalDate date) {
        return workingDays.contains(date.getDayOfWeek()) && !holidays.contains(date);
    }

    /** The Indian default: Monday to Friday, 09:00 to 18:00, Asia/Kolkata. */
    public static CalendarSpec defaultSpec() {
        return new CalendarSpec(ZoneId.of("Asia/Kolkata"),
                EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                LocalTime.of(9, 0), LocalTime.of(18, 0), Set.of());
    }
}
