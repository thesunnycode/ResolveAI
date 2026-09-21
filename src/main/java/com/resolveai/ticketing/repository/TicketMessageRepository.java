package com.resolveai.ticketing.repository;

import com.resolveai.ticketing.domain.TicketMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Messages on a ticket thread. */
public interface TicketMessageRepository extends JpaRepository<TicketMessage, Long> {

    List<TicketMessage> findByTicketIdOrderByCreatedAtAsc(Long ticketId);

    /**
     * Whether the first response has already been claimed on this ticket.
     *
     * <p>Advisory only. The authority is {@code idx_message_first_response}, a partial unique
     * index on {@code (ticket_id) WHERE is_first_response} — this check narrows the window,
     * the index closes it. Two agents replying in the same millisecond both see
     * {@code false} here; one of them then gets a constraint violation, which the service
     * catches and re-reads rather than surfacing as a 500.
     */
    boolean existsByTicketIdAndFirstResponseTrue(Long ticketId);

    long countByTicketId(Long ticketId);

    /**
     * Message counts for a page of tickets, in one query.
     *
     * <p>The list endpoint returns {@code messageCount} per row. Fetching it per ticket is
     * the textbook N+1: twenty-five extra round trips to render one page of a queue an agent
     * refreshes every few seconds.
     */
    @Query("""
            SELECT m.ticket.id, COUNT(m)
              FROM TicketMessage m
             WHERE m.ticket.id IN :ticketIds
             GROUP BY m.ticket.id
            """)
    List<Object[]> countByTicketIds(@Param("ticketIds") List<Long> ticketIds);
}
