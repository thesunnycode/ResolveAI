package com.resolveai.ticketing.repository;

import com.resolveai.ticketing.domain.TicketEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The audit timeline.
 *
 * <p>Read and insert only. There is deliberately no update or delete method, and there could
 * not usefully be one: {@code trg_ticket_event_immutable} raises an exception on both.
 */
public interface TicketEventRepository extends JpaRepository<TicketEvent, Long> {

    List<TicketEvent> findByTicketIdOrderByOccurredAtAsc(Long ticketId);

    long countByTicketId(Long ticketId);
}
