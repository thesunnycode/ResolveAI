package com.resolveai.sla.service;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.platform.tenant.TenantContext;
import com.resolveai.sla.domain.BusinessCalendar;
import com.resolveai.sla.repository.BusinessCalendarRepository;
import java.time.LocalTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code ADMIN} read/write path onto {@link BusinessCalendar} that its own class doc
 * anticipated ("the admin write path Phase 9 adds, which will need the same treatment on
 * the way in").
 *
 * <h2>{@code day_start}/{@code day_end} never go through Hibernate here either</h2>
 *
 * <p>Same reason {@link CalendarService#readHours} avoids it: {@code hibernate.jdbc.time_zone:
 * UTC} shifts a {@code TIME} column by the server's offset on both binding and reading,
 * which is corruption for a value with no date attached. {@code timezone} and
 * {@code workingDays} carry no such landmine and go through the entity normally; the two
 * {@code TIME} columns are read and written as text, cast in SQL, on a plain
 * {@link JdbcTemplate} connection.
 */
@Service
public class BusinessCalendarService {

    private static final Logger log = LoggerFactory.getLogger(BusinessCalendarService.class);

    private final BusinessCalendarRepository calendars;
    private final JdbcTemplate jdbc;

    public BusinessCalendarService(BusinessCalendarRepository calendars, JdbcTemplate jdbc) {
        this.calendars = calendars;
        this.jdbc = jdbc;
    }

    /** The value the entity's own mapped fields cannot be trusted to hold; see the class doc. */
    public record Hours(LocalTime start, LocalTime end) {
    }

    public record CalendarView(BusinessCalendar calendar, Hours hours) {
    }

    @Transactional
    public CalendarView current() {
        BusinessCalendar calendar = ensureExists();
        return new CalendarView(calendar, readHours(calendar.getId()));
    }

    @Transactional
    public CalendarView update(String timezone, Short[] workingDays, LocalTime dayStart, LocalTime dayEnd) {
        if (!dayEnd.isAfter(dayStart)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Day end must be after day start.");
        }
        if (workingDays.length == 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "At least one working day is required.");
        }

        BusinessCalendar calendar = ensureExists();
        calendar.setTimezone(timezone);
        calendar.setWorkingDays(workingDays);
        calendars.saveAndFlush(calendar);
        writeHours(calendar.getId(), dayStart, dayEnd);

        CalendarService.clearCache();
        log.info("Business calendar updated for tenant {}: {} {}-{} on days {}",
                calendar.getTenantId(), timezone, dayStart, dayEnd, java.util.Arrays.toString(workingDays));
        return new CalendarView(calendar, new Hours(dayStart, dayEnd));
    }

    private BusinessCalendar ensureExists() {
        return calendars.findFirstBy().orElseGet(() -> {
            // Same raw insert IamSeeder uses, and for the same reason: an entity save here
            // would bind the default 09:00/18:00 through the UTC TIME corruption this whole
            // class exists to avoid. The table's own column defaults are what actually land.
            Long tenantId = TenantContext.getRequired();
            jdbc.update("INSERT INTO business_calendar (tenant_id) VALUES (?)", tenantId);
            CalendarService.clearCache();
            return calendars.findFirstBy()
                    .orElseThrow(() -> new IllegalStateException(
                            "Business calendar insert for tenant " + tenantId + " did not produce a row"));
        });
    }

    private Hours readHours(Long calendarId) {
        return jdbc.queryForObject("""
                SELECT day_start::text AS day_start, day_end::text AS day_end
                  FROM business_calendar WHERE id = ?
                """,
                (rs, rowNum) -> new Hours(
                        LocalTime.parse(rs.getString("day_start")),
                        LocalTime.parse(rs.getString("day_end"))),
                calendarId);
    }

    private void writeHours(Long calendarId, LocalTime start, LocalTime end) {
        jdbc.update("""
                UPDATE business_calendar
                   SET day_start = ?::time, day_end = ?::time, updated_at = NOW()
                 WHERE id = ?
                """,
                start.toString(), end.toString(), calendarId);
    }
}
