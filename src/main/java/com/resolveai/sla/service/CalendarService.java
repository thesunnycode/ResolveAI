package com.resolveai.sla.service;

import com.resolveai.sla.domain.BusinessCalendar;
import com.resolveai.sla.domain.BusinessHoliday;
import com.resolveai.sla.domain.CalendarSpec;
import com.resolveai.sla.repository.BusinessCalendarRepository;
import com.resolveai.sla.repository.BusinessHolidayRepository;
import com.resolveai.platform.tenant.TenantContext;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads a tenant's {@link CalendarSpec}.
 *
 * <p>Read on every clock computation, which is several times per poller batch, so it is
 * cached — but <b>in a request-scoped {@code ThreadLocal} rather than in Redis or a
 * {@code @Cacheable}</b>. A calendar is three columns and a handful of holidays; the cost
 * being avoided is a repeated query within one unit of work, not a query per minute. A
 * distributed cache here would add an eviction problem (when does an edited calendar take
 * effect?) to save a lookup that Postgres answers from shared buffers.
 *
 * <p>Falling back to {@link CalendarSpec#defaultSpec()} when a tenant has no calendar is
 * deliberate and is the one place in the SLA engine where a default is acceptable: a
 * missing <i>policy</i> is a configuration error worth refusing over, because it means
 * nobody decided what the target is. A missing <i>calendar</i> has an obvious right
 * answer, and refusing to start a clock over it would make a tenant unusable for a reason
 * they cannot see.
 */
@Service
public class CalendarService {

    private final BusinessCalendarRepository calendars;
    private final BusinessHolidayRepository holidays;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    /**
     * Per-thread memo, cleared explicitly.
     *
     * <p>Keyed by tenant because the poller processes many tenants on one thread, and a
     * cache that ignored the key would hand one tenant's working hours to another — which
     * would not fail, it would just compute the wrong deadlines.
     */
    private static final ThreadLocal<java.util.Map<Long, CalendarSpec>> MEMO =
            ThreadLocal.withInitial(java.util.HashMap::new);

    public CalendarService(BusinessCalendarRepository calendars,
                           BusinessHolidayRepository holidays,
                           org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.calendars = calendars;
        this.holidays = holidays;
        this.jdbc = jdbc;
    }

    /** The current tenant's working hours. */
    @Transactional(readOnly = true)
    public CalendarSpec current() {
        Long tenantId = TenantContext.getRequired();
        return MEMO.get().computeIfAbsent(tenantId, id -> load());
    }

    private CalendarSpec load() {
        return calendars.findFirstBy()
                .map(this::toSpec)
                .orElseGet(CalendarSpec::defaultSpec);
    }

    private CalendarSpec toSpec(BusinessCalendar calendar) {
        Set<LocalDate> dates = holidays.findByCalendarId(calendar.getId()).stream()
                .map(BusinessHoliday::getHolidayDate)
                .collect(Collectors.toSet());
        WorkingDay day = readHours(calendar.getId());
        return calendar.toSpec(dates, day.start(), day.end());
    }

    /**
     * The working day, read as text and parsed.
     *
     * <h2>Why not just {@code calendar.getDayStart()}</h2>
     *
     * <p>Because it would be wrong by the server's UTC offset. {@code
     * hibernate.jdbc.time_zone: UTC} is set — correctly, and deliberately, so that every
     * instant is stored in one zone — but Hibernate applies it to {@code TIME} columns as
     * well as to {@code TIMESTAMP} ones, binding and reading them through a UTC
     * {@code Calendar}. A {@code TIME} has no date and therefore no instant to convert,
     * so the conversion is pure corruption: on an IST server {@code 09:00} in the database
     * comes back as {@code 14:30}.
     *
     * <p>The symptom is nasty precisely because it is not a crash. The working day is
     * still nine hours long, so every property holds and every test that only checks
     * durations passes — the clock simply runs against the wrong hours of the day, and
     * deadlines land in the middle of the night. It surfaced here only because a 24×7
     * calendar shifted {@code 23:59} past midnight and made {@code dayEnd} earlier than
     * {@code dayStart}, which {@code CalendarSpec} refuses.
     *
     * <p>Reading the two columns as {@code text} takes them out of Hibernate's temporal
     * handling entirely: Postgres renders {@code HH:MM:SS} and {@code LocalTime.parse}
     * reads it back, with no zone anywhere in the path. The entity's {@code LocalTime}
     * fields stay for the admin write path Phase 9 adds, which will need the same
     * treatment on the way in.
     */
    private WorkingDay readHours(Long calendarId) {
        return jdbc.queryForObject("""
                SELECT day_start::text AS day_start, day_end::text AS day_end
                  FROM business_calendar WHERE id = ?
                """,
                (rs, rowNum) -> new WorkingDay(
                        LocalTime.parse(rs.getString("day_start")),
                        LocalTime.parse(rs.getString("day_end"))),
                calendarId);
    }

    /** The two ends of a working day, free of any timezone conversion. */
    public record WorkingDay(LocalTime start, LocalTime end) {
    }

    /**
     * Drops the memo.
     *
     * <p>Called by the poller between records and by anything that edits a calendar. A
     * {@code ThreadLocal} on a pooled thread that is never cleared is a leak <i>and</i> a
     * correctness bug: the next request on that thread would compute deadlines from a
     * calendar that has since been edited.
     */
    public static void clearCache() {
        MEMO.remove();
    }
}
