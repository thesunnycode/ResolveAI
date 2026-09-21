package com.resolveai.sla.repository;

import com.resolveai.sla.domain.BusinessHoliday;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessHolidayRepository extends JpaRepository<BusinessHoliday, Long> {

    List<BusinessHoliday> findByCalendarId(Long calendarId);
}
