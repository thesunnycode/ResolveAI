package com.resolveai.sla.domain;

import java.time.ZonedDateTime;

/**
 * Arithmetic in business minutes.
 *
 * <p>Every SLA figure in the system is expressed in these. "Four hours to first response"
 * means four <i>working</i> hours: a ticket raised at 17:00 on a Friday with a Monday
 * holiday is due at 12:00 on the Tuesday, and a support team that is measured on wall-clock
 * time is measured on when its customers happened to write in.
 *
 * <h2>The boundary convention, stated once</h2>
 *
 * <p><b>The working window is half-open: {@code [dayStart, dayEnd)}.</b> An instant exactly
 * at {@code dayEnd} belongs to no working day — it is the moment after the last working
 * minute, not the first minute of the next day.
 *
 * <p>This is written down because the two methods below <b>must agree on it</b>, and the
 * consequence of their disagreeing is not a crash. It is an SLA that is one minute out per
 * day boundary crossed, in a system where the number matters only when somebody disputes a
 * breach and goes looking. The round-trip property —
 * {@code elapsedBusinessMinutes(t, add(t, n)) == n} — exists to catch precisely that
 * disagreement, because it composes both methods and any mismatch shows up immediately.
 *
 * <h2>Daylight saving</h2>
 *
 * <p>Both methods do their arithmetic on {@code LocalDateTime} within the calendar's zone
 * and resolve to a {@code ZonedDateTime} only at the end. Adding a {@code Duration} to a
 * {@code ZonedDateTime} across a spring-forward silently loses an hour, and 09:00 to 18:00
 * is nine working hours on every day of the year regardless of what the clocks did —
 * that is what "working hours" means to the people working them.
 */
public interface BusinessHours {

    /**
     * {@code from} plus {@code businessMinutes} of working time.
     *
     * <p>If {@code from} is outside working hours the clock has not started, so the result
     * is measured from the next working-hours start: a ticket raised at 03:00 is not four
     * hours into its budget by 07:00.
     *
     * @param businessMinutes zero or more. Zero from inside working hours returns
     *                        {@code from} unchanged; zero from outside returns the next
     *                        working-hours start, because that is when the clock begins.
     */
    ZonedDateTime add(ZonedDateTime from, long businessMinutes, CalendarSpec calendar);

    /**
     * Working minutes between two instants.
     *
     * @return {@code 0} when {@code to <= from}, never a negative number. A negative
     *         elapsed time would propagate into a remaining-budget figure and make a clock
     *         appear to run backwards.
     */
    long elapsedBusinessMinutes(ZonedDateTime from, ZonedDateTime to, CalendarSpec calendar);
}
