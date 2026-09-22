package com.resolveai.incidents.repository;

import com.resolveai.incidents.domain.Incident;
import com.resolveai.incidents.domain.IncidentStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Incidents. {@code @TenantId} on {@link Incident} filters every derived query here;
 * nothing native is needed for the simple lookups.
 */
public interface IncidentRepository extends JpaRepository<Incident, Long> {

    Optional<Incident> findByReference(String reference);

    /** Row-locked read, for confirm/reject/resolve — two concurrent mutations must not both win. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Incident i WHERE i.id = :id")
    Optional<Incident> findByIdForUpdate(@Param("id") Long id);

    /**
     * {@code status IN (...)}, backed by {@code idx_incident_open}. Callers pass
     * {@code EnumSet.of(PROPOSED, CONFIRMED, MITIGATED)} for "live" — kept as a parameter
     * rather than hard-coded here so the boundary-overlap check in {@code CorrelationGate}
     * and the board's status filter can both call the one method.
     */
    List<Incident> findByStatusIn(Collection<IncidentStatus> statuses);
}
