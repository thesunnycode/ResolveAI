package com.resolveai.sla;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.sla.domain.BusinessHours;
import com.resolveai.sla.domain.CalendarSpec;
import com.resolveai.sla.domain.DefaultBusinessHours;
import com.resolveai.testing.Property;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Five properties of business-hours arithmetic, over three calendars and a full year.
 *
 * <h2>Why these are properties and not examples</h2>
 *
 * <p>Every example-based test written by hand covers a case somebody already thought of.
 * The bugs in this code are in the cases nobody thought of: an instant exactly at
 * {@code dayEnd}; a budget that spans nine working days; a Monday holiday after a Friday
 * evening; the hour that does not exist on the second Sunday in March in New York. A
 * thousand random cases per property find those; a dozen hand-picked ones do not.
 *
 * <p>Writing them <i>before</i> the implementation feels like procrastination and is the
 * single most-skipped step in the plan. The reason it is not: {@link #roundTrip()} composes
 * both methods, and if it is written afterwards it gets written to match whatever the
 * implementation already does — including the boundary convention it got wrong.
 *
 * <h2>The three calendars</h2>
 *
 * <ul>
 *   <li><b>Asia/Kolkata</b>, Mon–Fri 09:00–18:00 — the default, and a zone with no DST at
 *       all, so a failure here is never a DST failure.
 *   <li><b>America/New_York</b>, Mon–Fri 09:00–17:00 — <b>has DST.</b> Two days a year have
 *       23 and 25 hours, and both still have eight working hours.
 *   <li><b>Asia/Dubai</b>, Sun–Thu 08:00–16:00 — <b>not a Monday-to-Friday week.</b> Any
 *       implementation that reasons about "the weekend" rather than about the working-day
 *       set is wrong here, and wrong in a way that produces plausible numbers.
 * </ul>
 *
 * <p>Each carries holidays that fall on a Monday and on a Friday, which are the two that
 * interact with the edge of a working week.
 */
class BusinessHoursPropertyTest {

    private final BusinessHours hours = new DefaultBusinessHours();

    /**
     * Fixed, so the suite is deterministic. A property test whose counterexample cannot be
     * reproduced tells you something is wrong and nothing about what.
     */
    private static final long SEED = 20260921L;

    // ── Calendars ───────────────────────────────────────────────────────────

    private static final CalendarSpec KOLKATA = new CalendarSpec(
            ZoneId.of("Asia/Kolkata"),
            EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            LocalTime.of(9, 0), LocalTime.of(18, 0),
            Set.of(LocalDate.of(2026, 1, 26),    // Monday — Republic Day
                   LocalDate.of(2026, 5, 1),     // Friday — Labour Day
                   LocalDate.of(2026, 8, 15)));

    private static final CalendarSpec NEW_YORK = new CalendarSpec(
            ZoneId.of("America/New_York"),
            EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            LocalTime.of(9, 0), LocalTime.of(17, 0),
            Set.of(LocalDate.of(2026, 1, 19),    // Monday — MLK Day
                   LocalDate.of(2026, 7, 3),     // Friday — Independence Day observed
                   LocalDate.of(2026, 11, 26)));

    private static final CalendarSpec DUBAI = new CalendarSpec(
            ZoneId.of("Asia/Dubai"),
            EnumSet.of(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                    DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
            LocalTime.of(8, 0), LocalTime.of(16, 0),
            Set.of(LocalDate.of(2026, 3, 2),     // Monday
                   LocalDate.of(2026, 12, 4)));  // Friday — already a non-working day

    private static final List<CalendarSpec> CALENDARS = List.of(KOLKATA, NEW_YORK, DUBAI);

    /** One generated case. */
    record Sample(ZonedDateTime from, long minutes, CalendarSpec calendar) {

        @Override
        public String toString() {
            return "%s (%s) + %d business minutes in %s %s %s-%s".formatted(
                    from, from.getDayOfWeek(), minutes, calendar.zone(),
                    calendar.workingDays().size() + "d/wk",
                    calendar.dayStart(), calendar.dayEnd());
        }
    }

    // ── Generation ──────────────────────────────────────────────────────────

    /**
     * An instant somewhere in 2026, and a budget from 1 to 5,000 minutes.
     *
     * <p>Five thousand minutes is roughly nine working days on a 9-hour calendar, which is
     * the point: an implementation that subtracts one day's remainder and stops is correct
     * for every budget under a day and wrong for these.
     *
     * <p>Every fourth sample is snapped to a boundary — {@code dayStart}, {@code dayEnd},
     * one minute either side — because uniform random instants land on a boundary with
     * probability about 1 in 500, and the boundaries are where the bugs are.
     */
    private static Sample generate(Random random) {
        CalendarSpec calendar = CALENDARS.get(random.nextInt(CALENDARS.size()));
        LocalDate date = LocalDate.of(2026, 1, 1).plusDays(random.nextInt(365));
        LocalTime time = switch (random.nextInt(4)) {
            case 0 -> calendar.dayStart();
            case 1 -> calendar.dayEnd();
            case 2 -> calendar.dayEnd().minusMinutes(1);
            default -> LocalTime.of(random.nextInt(24), random.nextInt(60));
        };
        long minutes = 1 + random.nextInt(5000);
        return new Sample(ZonedDateTime.of(LocalDateTime.of(date, time), calendar.zone()),
                minutes, calendar);
    }

    /** Shrink the budget first — it is the dimension that makes a counterexample unreadable. */
    private static List<Sample> shrinkSample(Sample s) {
        List<Sample> candidates = new ArrayList<>();
        for (long smaller : Property.halvings(s.minutes())) {
            candidates.add(new Sample(s.from(), smaller, s.calendar()));
        }
        return candidates;
    }

    // ── The five properties ─────────────────────────────────────────────────

    @Test
    @DisplayName("identity: adding zero inside working hours changes nothing")
    void identity() {
        Property.forAll("identity", SEED,
                BusinessHoursPropertyTest::generate,
                s -> List.of(),
                s -> {
                    if (!insideWorkingHours(s.from(), s.calendar())) {
                        return;  // the clock has not started; add(t, 0) moves to when it does
                    }
                    assertThat(hours.add(s.from(), 0, s.calendar())).isEqualTo(s.from());
                });
    }

    @Test
    @DisplayName("inside hours: any positive addition lands on a working day, within hours")
    void landsInsideWorkingHours() {
        Property.forAll("inside working hours", SEED,
                BusinessHoursPropertyTest::generate, BusinessHoursPropertyTest::shrinkSample,
                s -> {
                    ZonedDateTime result = hours.add(s.from(), s.minutes(), s.calendar());
                    assertThat(insideWorkingHours(result, s.calendar()))
                            .as("%s landed at %s, which is not working time", s, result)
                            .isTrue();
                });
    }

    @Test
    @DisplayName("additivity: add(add(t, a), b) == add(t, a + b)")
    void additivity() {
        Property.forAll("additivity", SEED + 1,
                BusinessHoursPropertyTest::generate, BusinessHoursPropertyTest::shrinkSample,
                s -> {
                    long a = s.minutes() / 3;
                    long b = s.minutes() - a;
                    assertThat(hours.add(hours.add(s.from(), a, s.calendar()), b, s.calendar()))
                            .isEqualTo(hours.add(s.from(), s.minutes(), s.calendar()));
                });
    }

    @Test
    @DisplayName("monotonicity: a smaller budget never lands later")
    void monotonicity() {
        Property.forAll("monotonicity", SEED + 2,
                BusinessHoursPropertyTest::generate, BusinessHoursPropertyTest::shrinkSample,
                s -> {
                    long smaller = s.minutes() / 2;
                    assertThat(hours.add(s.from(), smaller, s.calendar()))
                            .isBeforeOrEqualTo(hours.add(s.from(), s.minutes(), s.calendar()));
                });
    }

    /**
     * <b>The property that finds the real bugs.</b>
     *
     * <p>It composes both methods, so any disagreement between them surfaces here — and
     * they will disagree, at {@code dayEnd}, at {@code dayStart}, and for a budget longer
     * than one working day. If this is still red after both are implemented, the mismatch
     * is a boundary convention, not an algorithm.
     */
    @Test
    @DisplayName("round-trip: elapsed(t, add(t, n)) == n")
    void roundTrip() {
        Property.forAll("round-trip", SEED + 3,
                BusinessHoursPropertyTest::generate, BusinessHoursPropertyTest::shrinkSample,
                s -> {
                    ZonedDateTime target = hours.add(s.from(), s.minutes(), s.calendar());
                    // Measured from the clock's actual start. When `from` is outside
                    // working hours the clock starts later, and elapsed(from, target)
                    // would legitimately report the same n - but anchoring makes the
                    // property about the arithmetic rather than about that adjustment.
                    ZonedDateTime anchor = hours.add(s.from(), 0, s.calendar());
                    assertThat(hours.elapsedBusinessMinutes(anchor, target, s.calendar()))
                            .as("%s -> %s", s, target)
                            .isEqualTo(s.minutes());
                });
    }

    // ── Helper ──────────────────────────────────────────────────────────────

    /** Half-open: {@code [dayStart, dayEnd)}. {@code dayEnd} itself is not working time. */
    private static boolean insideWorkingHours(ZonedDateTime instant, CalendarSpec calendar) {
        LocalDateTime local = instant.withZoneSameInstant(calendar.zone()).toLocalDateTime();
        return calendar.isWorkingDay(local.toLocalDate())
                && !local.toLocalTime().isBefore(calendar.dayStart())
                && local.toLocalTime().isBefore(calendar.dayEnd());
    }
}
