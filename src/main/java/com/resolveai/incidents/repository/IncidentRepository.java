package com.resolveai.incidents.repository;

import com.resolveai.incidents.domain.Incident;
import com.resolveai.incidents.domain.IncidentStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Incidents. {@code @TenantId} on {@link Incident} filters every derived query here;
 * nothing native is needed for the simple lookups.
 */
public interface IncidentRepository extends JpaRepository<Incident, Long> {

    Optional<Incident> findByReference(String reference);

    /**
     * {@code status IN (...)}, backed by {@code idx_incident_open}. Callers pass
     * {@code EnumSet.of(PROPOSED, CONFIRMED, MITIGATED)} for "live" — kept as a parameter
     * rather than hard-coded here so the boundary-overlap check in {@code CorrelationGate}
     * and the board's status filter can both call the one method.
     */
    List<Incident> findByStatusIn(Collection<IncidentStatus> statuses);
}
