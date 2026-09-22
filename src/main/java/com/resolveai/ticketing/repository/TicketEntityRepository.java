package com.resolveai.ticketing.repository;

import com.resolveai.ticketing.domain.TicketEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Deterministically-extracted ticket entities. See {@link TicketEntity}.
 */
public interface TicketEntityRepository extends JpaRepository<TicketEntity, Long> {

    List<TicketEntity> findByTicketId(Long ticketId);

    /**
     * Every extracted entity for a set of tickets, in one round trip.
     *
     * <p>{@code TicketClusterer} loads a whole candidate window this way and builds the
     * per-ticket entity sets in memory, for the same reason it loads embeddings up front
     * rather than querying pgvector per pair — see its class comment.
     */
    List<TicketEntity> findByTicketIdIn(List<Long> ticketIds);

    /**
     * Idempotent insert for one (ticket, type, value) triple.
     *
     * <p>Native, and {@code ON CONFLICT DO NOTHING} rather than a JPA {@code save} guarded
     * by an existence check: a retried triage attempt re-extracts the same entities from the
     * same text, and {@code uq_ticket_entity} means the second insert is a no-op rather than
     * a constraint violation the caller has to catch.
     */
    @Modifying
    @Query(value = """
            INSERT INTO ticket_entity (ticket_id, tenant_id, entity_type, entity_value)
            VALUES (:ticketId, :tenantId, :entityType, :entityValue)
            ON CONFLICT (ticket_id, entity_type, entity_value) DO NOTHING
            """, nativeQuery = true)
    void upsert(@Param("ticketId") Long ticketId, @Param("tenantId") Long tenantId,
               @Param("entityType") String entityType, @Param("entityValue") String entityValue);
}
