package com.resolveai.sla.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * A non-working day.
 *
 * <p><b>{@code holidayDate} is a {@link LocalDate}, not an instant.</b> A public holiday is
 * a calendar day in the tenant's own zone - Republic Day is the 26th of January in
 * Kolkata, and asking when it "starts" in UTC is a question with no useful answer.
 *
 * <p>No {@code tenant_id}: it hangs off {@code business_calendar}, which has one, and
 * {@code uq_calendar_tenant} makes that a one-to-one. A second tenant column here would be
 * a second thing that could disagree.
 */
@Entity
@Table(name = "business_holiday")
public class BusinessHoliday {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "calendar_id", nullable = false)
    private Long calendarId;

    @Column(name = "holiday_date", nullable = false)
    private LocalDate holidayDate;

    @Column(nullable = false, length = 80)
    private String name;

    protected BusinessHoliday() {
        // JPA
    }

    public BusinessHoliday(Long calendarId, LocalDate holidayDate, String name) {
        this.calendarId = calendarId;
        this.holidayDate = holidayDate;
        this.name = name;
    }

    public Long getId() { return id; }
    public Long getCalendarId() { return calendarId; }
    public LocalDate getHolidayDate() { return holidayDate; }
    public String getName() { return name; }
}
