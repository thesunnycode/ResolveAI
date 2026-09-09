package com.resolveai.incidents.repository;

import com.resolveai.incidents.domain.IncidentTicket;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

/**
 * Links between incidents and tickets. No {@code @TenantId} here — see
 * {@link IncidentTicket}'s class comment — so every query is scoped explicitly by
 * {@code incidentId} or {@code ticketId}, both of which are themselves reached only through
 * a tenant-checked load.
 */
public interface IncidentTicketRepository extends JpaRepository<IncidentTicket, Long> {

    /** Live links for an incident, backed by {@code idx_incident_tickets}. */
    @Query("SELECT it FROM IncidentTicket it "
           + "WHERE it.incidentId = :incidentId AND it.detachedAt IS NULL")
    List<IncidentTicket> findLiveByIncidentId(@Param("incidentId") Long incidentId);

    /** Every link, live and detached — the detail view's {@code detachedTickets} array. */
    List<IncidentTicket> findByIncidentId(Long incidentId);

    /**
     * The ticket's one live link, if any. Backed by {@code uq_incident_ticket_live}; at
     * most one row can ever come back.
     */
    @Query("SELECT it FROM IncidentTicket it "
           + "WHERE it.ticketId = :ticketId AND it.detachedAt IS NULL")
    Optional<IncidentTicket> findLiveByTicketId(@Param("ticketId") Long ticketId);

    /** Row-locked read, for the detach path — two concurrent detaches must not both succeed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT it FROM IncidentTicket it WHERE it.id = :id")
    Optional<IncidentTicket> findByIdForUpdate(@Param("id") Long id);

    long countByIncidentIdAndDetachedAtIsNull(Long incidentId);
}
