package com.resolveai.incidents.repository;

import com.resolveai.incidents.domain.IncidentUpdateDelivery;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Per-ticket delivery rows for a fan-out. See {@link IncidentUpdateDelivery}. */
public interface IncidentUpdateDeliveryRepository
        extends JpaRepository<IncidentUpdateDelivery, Long> {

    List<IncidentUpdateDelivery> findByIncidentUpdateId(Long incidentUpdateId);

    /**
     * The conditional update that <b>is</b> {@code FanoutWorker}'s idempotency mechanism.
     *
     * <p>Returns the number of rows changed — 1 on a first delivery, 0 on a redelivery,
     * because the {@code WHERE status = 'PENDING'} predicate no longer matches a row
     * already {@code SENT}. The caller uses that count to decide whether to create a
     * notification, never a separate {@code SELECT ... status} beforehand: the same
     * read-then-act race that {@code TicketRepository.assignIfUnassigned}'s comment
     * explains would exist here too.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE IncidentUpdateDelivery d SET d.status = 'SENT', d.deliveredAt = :now "
           + "WHERE d.id = :deliveryId AND d.status = 'PENDING'")
    int markSent(@Param("deliveryId") Long deliveryId,
                @Param("now") java.time.OffsetDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE IncidentUpdateDelivery d SET d.attempts = d.attempts + 1, "
           + "d.lastError = :error WHERE d.id = :deliveryId")
    void recordFailure(@Param("deliveryId") Long deliveryId, @Param("error") String error);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE IncidentUpdateDelivery d SET d.status = 'FAILED' WHERE d.id = :deliveryId")
    void markFailed(@Param("deliveryId") Long deliveryId);
}
