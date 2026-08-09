package com.resolveai.sla.web;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.common.security.IsAdmin;
import com.resolveai.sla.service.BusinessCalendarService;
import com.resolveai.sla.service.BusinessCalendarService.CalendarView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The tenant's own business hours. {@code ADMIN} only - every SLA target is read against this. */
@RestController
@RequestMapping("/api/v1/admin/calendar")
public class BusinessCalendarController {

    private final BusinessCalendarService service;

    public BusinessCalendarController(BusinessCalendarService service) {
        this.service = service;
    }

    public record CalendarResponse(
            String timezone,
            List<Integer> workingDays,
            String dayStart,
            String dayEnd) {

        static CalendarResponse of(CalendarView view) {
            return new CalendarResponse(
                    view.calendar().getTimezone(),
                    java.util.Arrays.stream(view.calendar().getWorkingDays()).map(Short::intValue).toList(),
                    view.hours().start().toString(),
                    view.hours().end().toString());
        }
    }

    public record UpdateCalendarRequest(
            @NotNull(message = "timezone is required")
            String timezone,

            @NotEmpty(message = "At least one working day is required")
            List<Integer> workingDays,

            @NotNull(message = "dayStart is required")
            String dayStart,

            @NotNull(message = "dayEnd is required")
            String dayEnd) {
    }

    @GetMapping
    @IsAdmin
    public CalendarResponse get() {
        return CalendarResponse.of(service.current());
    }

    @PutMapping
    @IsAdmin
    public CalendarResponse update(@Valid @RequestBody UpdateCalendarRequest request) {
        Short[] days = request.workingDays().stream().map(Integer::shortValue).toArray(Short[]::new);
        LocalTime start = parseTime(request.dayStart(), "dayStart");
        LocalTime end = parseTime(request.dayEnd(), "dayEnd");
        return CalendarResponse.of(service.update(request.timezone(), days, start, end));
    }

    private static LocalTime parseTime(String raw, String field) {
        try {
            return LocalTime.parse(raw);
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, field + " must be HH:mm.", e);
        }
    }
}
