package com.resolveai.sla.repository;

import com.resolveai.sla.domain.SlaClockSegment;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SlaClockSegmentRepository extends JpaRepository<SlaClockSegment, Long> {

    List<SlaClockSegment> findBySlaRecordIdOrderByStartedAtAsc(Long slaRecordId);

    Optional<SlaClockSegment> findBySlaRecordIdAndEndedAtIsNull(Long slaRecordId);

    /**
     * Closes the open segment, if there is one, and says whether it did anything.
     *
     * <h2>Why a statement and not {@code segment.close(now)}</h2>
     *
     * <p>Two reasons, and the second is the one that costs an afternoon to find.
     *
     * <p>First, the affected-row count <i>is</i> the concurrency answer. Two requests
     * pausing the same clock both call this; one gets {@code 1} and does the rest of the
     * work, the other gets {@code 0} and returns the current state as an idempotent
     * {@code 200}. With an entity read followed by a write, both would see an open
     * segment and both would insert a paused one, and {@code uq_segment_open} would turn
     * the second into a 500.
     *
     * <p>Second, <b>Hibernate does not flush in the order you wrote the code.</b> Closing
     * one segment and adding another through a managed collection lets it order the INSERT
     * before the UPDATE, which trips {@code uq_segment_open} with a violation that names
     * the constraint and points at the insert — while the actual mistake is the ordering.
     * Explicit statements in an explicit order cannot be reordered.
     *
     * <p>{@code AND ended_at IS NULL} also satisfies {@code trg_segment_close_once}, which
     * raises if a closed segment is touched.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE sla_clock_segment SET ended_at = :endedAt
             WHERE sla_record_id = :recordId AND ended_at IS NULL
            """, nativeQuery = true)
    int closeOpenSegment(@Param("recordId") Long recordId,
                         @Param("endedAt") OffsetDateTime endedAt);
}
