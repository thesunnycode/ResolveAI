package com.resolveai.sla.repository;

import com.resolveai.sla.domain.BusinessCalendar;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** One calendar per tenant — {@code uq_calendar_tenant} makes that a database fact. */
public interface BusinessCalendarRepository extends JpaRepository<BusinessCalendar, Long> {

    /**
     * The tenant's calendar.
     *
     * <p>No tenant parameter: {@code @TenantId} supplies the predicate, and there is at
     * most one row, so "find the first" is "find the only".
     */
    Optional<BusinessCalendar> findFirstBy();
}
