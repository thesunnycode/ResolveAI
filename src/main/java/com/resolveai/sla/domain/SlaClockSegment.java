package com.resolveai.sla.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;

/**
 * One stretch of time during which a clock was either running or paused.
 *
 * <p><b>Append-only, with exactly one mutation allowed:</b> a segment is closed once, by
 * setting {@code endedAt}. {@code trg_segment_close_once} rejects any update to a segment
 * that is already closed, and the Java side matches: there is no setter for
 * {@code startedAt} at all, and {@code close} refuses a second call.
 *
 * <p>{@code uq_segment_open} - a partial unique index on
 * {@code (sla_record_id) WHERE ended_at IS NULL} - guarantees at most one open segment per
 * record. That is what makes two concurrent pauses resolve to one, and it is also the
 * constraint that produces a confusing violation if a service mutates a cascading
 * collection and lets Hibernate order the INSERT before the UPDATE at flush time. See
 * {@code SlaClockService} for why the pause path uses explicit repository calls instead.
 *
 * <p>No {@code tenant_id} column, deliberately: a segment is reachable only through its
 * {@code sla_record}, which has one, and a second copy would be a second thing that could
 * disagree. It also means this entity carries no {@code @TenantId} and is not filtered -
 * every query for segments goes through a record id that was itself tenant-filtered.
 */
@Entity
@Table(name = "sla_clock_segment")
public class SlaClockSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sla_record_id", nullable = false, updatable = false)
    private Long slaRecordId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private SegmentState state;

    @Column(name = "started_at", nullable = false, updatable = false)
    private OffsetDateTime startedAt;

    /** Null means this is the open segment. Settable exactly once. */
    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "pause_reason", length = 40, updatable = false)
    private PauseReason pauseReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected SlaClockSegment() {
        // JPA
    }

    public SlaClockSegment(Long slaRecordId, SegmentState state, OffsetDateTime startedAt,
                           PauseReason pauseReason) {
        this.slaRecordId = slaRecordId;
        this.state = state;
        this.startedAt = startedAt;
        this.pauseReason = pauseReason;
    }

    public Long getId() { return id; }
    public Long getSlaRecordId() { return slaRecordId; }
    public SegmentState getState() { return state; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public OffsetDateTime getEndedAt() { return endedAt; }
    public PauseReason getPauseReason() { return pauseReason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public boolean isOpen() {
        return endedAt == null;
    }

    public boolean isRunning() {
        return state == SegmentState.RUNNING;
    }

    /**
     * Closes the segment. Package-private: only the SLA services may do this, and they do
     * it through a conditional UPDATE rather than through the entity in the concurrent
     * path - see {@code SlaClockSegmentRepository.closeOpenSegment}.
     */
    void close(OffsetDateTime at) {
        if (this.endedAt != null) {
            throw new IllegalStateException(
                    "Segment " + id + " is already closed at " + endedAt);
        }
        this.endedAt = at;
    }
}
