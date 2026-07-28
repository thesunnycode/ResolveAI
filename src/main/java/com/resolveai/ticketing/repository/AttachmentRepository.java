package com.resolveai.ticketing.repository;

import com.resolveai.ticketing.domain.Attachment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Attachments.
 *
 * <p>The upload endpoints are Phase 5 Task 17, which was cut — see the README. The entity
 * and this repository exist because the table is referenced by {@code ticket_message} and
 * the detail response has to serialise an (always empty) attachment array either way.
 */
public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    List<Attachment> findByMessageIdIn(List<Long> messageIds);
}
