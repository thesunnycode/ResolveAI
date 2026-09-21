package com.resolveai.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resolveai.sla.domain.BusinessHours;
import com.resolveai.sla.domain.CalendarSpec;
import com.resolveai.sla.domain.DefaultBusinessHours;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Worked examples, as documentation.
 *
 * <p>The properties in {@code BusinessHoursPropertyTest} prove the arithmetic is
 * self-consistent across a thousand random cases. They do <b>not</b> prove it computes the
 * thing a human would call correct — an implementation that measured everything in
 * wall-clock minutes would satisfy identity, additivity, monotonicity and round-trip
 * perfectly. These examples pin the answers somebody would check by counting on their
 * fingers.
 *
 * <p>Together the two files cover both halves: properties for internal consistency across
 * cases nobody enumerated, examples for external correctness on cases anybody can verify.
 */
class BusinessHoursExampleTest {

    private final BusinessHours hours = new DefaultBusinessHours();

    private static final ZoneId KOLKATA_ZONE = ZoneId.of("Asia/Kolkata");
    private static final ZoneId NEW_YORK_ZONE = ZoneId.of("America/New_York");

    private static final Set<DayOfWeek> MON_TO_FRI = EnumSet.of(DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);

    /** Mon–Fri 09:00–18:00, with Monday 1 June 2026 as a holiday. */
    private static final CalendarSpec KOLKATA_WITH_MONDAY_HOLIDAY = new CalendarSpec(
            KOLKATA_ZONE, MON_TO_FRI, LocalTime.of(9, 0), LocalTime.of(18, 0),
            Set.of(LocalDate.of(2026, 6, 1)));

    private static final CalendarSpec KOLKATA_PLAIN = new CalendarSpec(
            KOLKATA_ZONE, MON_TO_FRI, LocalTime.of(9, 0), LocalTime.of(18, 0), Set.of());

    /** Mon–Fri 09:00–17:00 in a zone with daylight saving. */
    private static final CalendarSpec NEW_YORK = new CalendarSpec(
            NEW_YORK_ZONE, MON_TO_FRI, LocalTime.of(9, 0), LocalTime.of(17, 0), Set.of());

    @Test
    @DisplayName("Friday 16:30 + 4 business hours, with a Monday holiday, is Tuesday 11:30")
    void weekendAndHolidayAreSkipped() {
        // 90 minutes left on the Friday (16:30 to 18:00), so 150 remain.
        // Saturday and Sunday are not working days. Monday is a holiday.
        // Tuesday starts at 09:00; 150 minutes later is 11:30.
        ZonedDateTime friday = ZonedDateTime.of(
                LocalDateTime.of(2026, 5, 29, 16, 30), KOLKATA_ZONE);

        ZonedDateTime result = hours.add(friday, 4 * 60, KOLKATA_WITH_MONDAY_HOLIDAY);

        assertThat(result).isEqualTo(ZonedDateTime.of(
                LocalDateTime.of(2026, 6, 2, 11, 30), KOLKATA_ZONE));
    }

    @Test
    @DisplayName("a full working week is exactly five days later at the same time")
    void aFullWeek() {
        // 5 x 9 hours = 2700 minutes. Monday 09:00 plus a week of work is Monday 09:00.
        ZonedDateTime monday = ZonedDateTime.of(
                LocalDateTime.of(2026, 3, 2, 9, 0), KOLKATA_ZONE);

        assertThat(hours.add(monday, 5 * 9 * 60, KOLKATA_PLAIN))
                .isEqualTo(ZonedDateTime.of(LocalDateTime.of(2026, 3, 9, 9, 0), KOLKATA_ZONE));
    }

    /**
     * The DST case, and the reason the arithmetic is done on {@code LocalDateTime}.
     *
     * <p>Sunday 8 March 2026 is a spring-forward in New York: the day is 23 hours long.
     * Nothing about the working week changes — Friday to Monday is still one working day
     * of elapsed business time. An implementation that added a {@code Duration} to a
     * {@code ZonedDateTime} would be an hour out here and correct on all 363 other days.
     */
    @Test
    @DisplayName("a spring-forward weekend does not shift the working day")
    void daylightSavingDoesNotLeak() {
        ZonedDateTime fridayClose = ZonedDateTime.of(
                LocalDateTime.of(2026, 3, 6, 17, 0), NEW_YORK_ZONE);
        ZonedDateTime mondayClose = ZonedDateTime.of(
                LocalDateTime.of(2026, 3, 9, 17, 0), NEW_YORK_ZONE);

        // One working day of 8 hours, even though 71 physical hours separate them and one
        // of those days was 23 hours long.
        assertThat(hours.elapsedBusinessMinutes(fridayClose, mondayClose, NEW_YORK))
                .isEqualTo(8 * 60);

        // And the other direction: Friday 16:00 plus 2 business hours is Monday 10:00.
        assertThat(hours.add(ZonedDateTime.of(LocalDateTime.of(2026, 3, 6, 16, 0),
                        NEW_YORK_ZONE), 120, NEW_YORK))
                .isEqualTo(ZonedDateTime.of(LocalDateTime.of(2026, 3, 9, 10, 0), NEW_YORK_ZONE));
    }

    @Test
    @DisplayName("a ticket raised at 03:00 does not burn budget before the day starts")
    void theClockStartsWhenTheDayDoes() {
        ZonedDateTime middleOfTheNight = ZonedDateTime.of(
                LocalDateTime.of(2026, 3, 3, 3, 0), KOLKATA_ZONE);

        // Zero minutes from outside hours returns the moment the clock starts, which is
        // also what makes add(t, 0) a usable "when does this begin?" query.
        assertThat(hours.add(middleOfTheNight, 0, KOLKATA_PLAIN))
                .isEqualTo(ZonedDateTime.of(LocalDateTime.of(2026, 3, 3, 9, 0), KOLKATA_ZONE));
        assertThat(hours.add(middleOfTheNight, 60, KOLKATA_PLAIN))
                .isEqualTo(ZonedDateTime.of(LocalDateTime.of(2026, 3, 3, 10, 0), KOLKATA_ZONE));
    }

    /**
     * The boundary case the round-trip property exists to police.
     *
     * <p>A budget that exactly fills the remaining day lands on the next working day's
     * start, not on {@code dayEnd}. Under the half-open convention {@code dayEnd} is not
     * working time, so landing there would put the clock at an instant that
     * {@code elapsedBusinessMinutes} does not count — and the two methods would disagree
     * by one full day boundary.
     */
    @Test
    @DisplayName("exactly filling the day rolls to the next day's start, not to dayEnd")
    void exactlyFillingTheDayRollsOver() {
        ZonedDateTime tuesdayNine = ZonedDateTime.of(
                LocalDateTime.of(2026, 3, 3, 9, 0), KOLKATA_ZONE);

        assertThat(hours.add(tuesdayNine, 9 * 60, KOLKATA_PLAIN))
                .isEqualTo(ZonedDateTime.of(LocalDateTime.of(2026, 3, 4, 9, 0), KOLKATA_ZONE));
    }

    @Test
    @DisplayName("elapsed over a weekend counts only the working minutes")
    void elapsedSkipsTheWeekend() {
        ZonedDateTime fridayFive = ZonedDateTime.of(
                LocalDateTime.of(2026, 3, 6, 17, 0), KOLKATA_ZONE);
        ZonedDateTime mondayTen = ZonedDateTime.of(
                LocalDateTime.of(2026, 3, 9, 10, 0), KOLKATA_ZONE);

        // 60 minutes on the Friday (17:00-18:00) plus 60 on the Monday (09:00-10:00).
        assertThat(hours.elapsedBusinessMinutes(fridayFive, mondayTen, KOLKATA_PLAIN))
                .isEqualTo(120);
    }

    @Test
    @DisplayName("elapsed is zero, never negative, when the interval runs backwards")
    void backwardsIntervalsAreZero() {
        ZonedDateTime later = ZonedDateTime.of(LocalDateTime.of(2026, 3, 9, 10, 0), KOLKATA_ZONE);
        ZonedDateTime earlier = ZonedDateTime.of(LocalDateTime.of(2026, 3, 6, 17, 0), KOLKATA_ZONE);

        // A negative figure here would propagate into a remaining budget and make a clock
        // appear to run backwards in the UI.
        assertThat(hours.elapsedBusinessMinutes(later, earlier, KOLKATA_PLAIN)).isZero();
        assertThat(hours.elapsedBusinessMinutes(later, later, KOLKATA_PLAIN)).isZero();
    }

    @Test
    @DisplayName("a calendar with no working days is rejected at construction")
    void emptyCalendarIsRefused() {
        // Otherwise add() searches for a working day it will never find. Rejecting at
        // construction turns an infinite loop into a message naming the calendar.
        assertThatThrownBy(() -> new CalendarSpec(KOLKATA_ZONE, Set.of(),
                LocalTime.of(9, 0), LocalTime.of(18, 0), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one working day");

        assertThatThrownBy(() -> new CalendarSpec(KOLKATA_ZONE, MON_TO_FRI,
                LocalTime.of(18, 0), LocalTime.of(9, 0), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dayEnd must be after dayStart");
    }

    @Test
    @DisplayName("a 24-hour calendar still behaves, because some tenants run one")
    void aroundTheClockCalendar() {
        // 00:00-23:59 seven days a week: the nearest this model gets to a 24/7 desk.
        // Worth a test because it is the degenerate case where every guard about
        // advancing to the next working day is never exercised.
        CalendarSpec alwaysOn = new CalendarSpec(KOLKATA_ZONE,
                EnumSet.allOf(DayOfWeek.class), LocalTime.MIDNIGHT, LocalTime.of(23, 59),
                Set.of());
        ZonedDateTime start = ZonedDateTime.of(
                LocalDateTime.of(2026, 3, 7, 22, 0), KOLKATA_ZONE);

        // 119 minutes to 23:59, then the remaining 1 minute at the start of the 8th.
        assertThat(hours.add(start, 120, alwaysOn))
                .isEqualTo(ZonedDateTime.of(LocalDateTime.of(2026, 3, 8, 0, 1), KOLKATA_ZONE));
    }
}
