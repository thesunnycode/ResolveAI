package com.resolveai.incidents.repository;

import com.resolveai.incidents.domain.IncidentUpdate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Published updates, ordered per {@code idx_update_incident}. */
public interface IncidentUpdateRepository extends JpaRepository<IncidentUpdate, Long> {

    List<IncidentUpdate> findByIncidentIdOrderByPublishedAtAsc(Long incidentId);
}
