package com.resolveai.sla.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * A tenant's working hours.
 *
 * <p><b>{@code timezone} holds an IANA name, never a UTC offset.</b> {@code Asia/Kolkata},
 * not {@code +05:30}. An offset does not know about daylight saving, so a New York
 * calendar stored as {@code -05:00} runs an hour late for eight months of the year - and
 * every SLA computed from it is quietly wrong for those eight months, in a direction that
 * favours the vendor.
 *
 * <p>{@code workingDays} is a {@code Short[]} of ISO-8601 day numbers (Monday = 1),
 * converted to {@link DayOfWeek} at the boundary. Not every support desk runs Monday to
 * Friday: Gulf teams commonly run Sunday to Thursday, and an implementation that reasons
 * about "the weekend" instead of about this set is wrong for them in a way that produces
 * plausible-looking numbers.
 *
 * <p>Unlike {@code sla_record}, this table has no {@code set_updated_at} trigger - nothing
 * writes it with native SQL - so {@code @UpdateTimestamp} is the right owner here.
 */
@Entity
@Table(name = "business_calendar")
public class BusinessCalendar {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 64)
    private String timezone = "Asia/Kolkata";

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "working_days", nullable = false, columnDefinition = "smallint[]")
    private Short[] workingDays = {1, 2, 3, 4, 5};

    @Column(name = "day_start", nullable = false)
    private LocalTime dayStart = LocalTime.of(9, 0);

    @Column(name = "day_end", nullable = false)
    private LocalTime dayEnd = LocalTime.of(18, 0);

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected BusinessCalendar() {
        // JPA
    }

    public Long getId() { return id; }
    public Long getTenantId() { return tenantId; }
    public String getTimezone() { return timezone; }
    public Short[] getWorkingDays() { return workingDays.clone(); }
    public LocalTime getDayStart() { return dayStart; }
    public LocalTime getDayEnd() { return dayEnd; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setTimezone(String timezone) { this.timezone = timezone; }
    public void setWorkingDays(Short[] days) { this.workingDays = days.clone(); }
    public void setHours(LocalTime start, LocalTime end) {
        this.dayStart = start;
        this.dayEnd = end;
    }

    /**
     * The value object the arithmetic actually uses.
     *
     * <p>Conversion happens here rather than in {@code BusinessHours} so that the pure
     * arithmetic never sees a JPA entity - which is what lets it be unit-tested a thousand
     * times a second with no database.
     */
    public CalendarSpec toSpec(Set<java.time.LocalDate> holidays) {
        return toSpec(holidays, dayStart, dayEnd);
    }

    /**
     * The same, with the working day supplied by the caller.
     *
     * <p>{@code CalendarService} reads the two {@code TIME} columns as text and passes
     * them in here, because {@code hibernate.jdbc.time_zone: UTC} shifts a {@code TIME}
     * by the server's offset on the way out of the database — a conversion that is
     * meaningless for a value with no date attached. The mapped fields below are still
     * the schema of record; they are simply not trustworthy to read on a server that is
     * not running in UTC.
     */
    public CalendarSpec toSpec(Set<java.time.LocalDate> holidays,
                               java.time.LocalTime start, java.time.LocalTime end) {
        Set<DayOfWeek> days = Arrays.stream(workingDays)
                .map(d -> DayOfWeek.of(d.intValue()))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
        return new CalendarSpec(ZoneId.of(timezone), days, start, end, holidays);
    }
}
