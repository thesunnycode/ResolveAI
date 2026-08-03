package com.resolveai.sla.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A record that a rung fired. <b>Fully immutable: constructor only, no setters.</b>
 *
 * <h2>This row is the exactly-once mechanism</h2>
 *
 * <p>{@code uq_escalation_rung}, a unique index on {@code (sla_record_id, rung)}, is what
 * turns an at-least-once poller into an exactly-once <i>effect</i>. The escalation row is
 * inserted <b>before</b> the notification is created; if two poller runs pick the same
 * record, the second one's INSERT violates the constraint and it stops there, having sent
 * nothing.
 *
 * <p>That is the payoff for the whole polling design: no distributed lock, no leader
 * election, no coordination of any kind between instances. A unique index does the work,
 * and the cheap "claim ids and re-check under the lock" strategy in the poller is only
 * viable <i>because</i> this constraint exists.
 *
 * <p>{@code trg_escalation_immutable} rejects UPDATE and DELETE, and the absence of
 * setters here matches it - so the compiler catches the mistake before the trigger has to.
 */
@Entity
@Table(name = "sla_escalation")
public class SlaEscalation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sla_record_id", nullable = false, updatable = false)
    private Long slaRecordId;

    @Column(nullable = false, updatable = false)
    private short rung;

    @CreationTimestamp
    @Column(name = "fired_at", nullable = false, updatable = false)
    private OffsetDateTime firedAt;

    @Column(name = "notification_id", updatable = false)
    private Long notificationId;

    /**
     * What the elapsed figure actually was when this fired.
     *
     * <p>Recorded rather than recomputed, because the segments can be added to afterwards
     * and "why did this fire at 52%?" is a question asked months later.
     */
    @Column(name = "elapsed_minutes_at_fire", nullable = false, updatable = false)
    private int elapsedMinutesAtFire;

    protected SlaEscalation() {
        // JPA
    }

    public SlaEscalation(Long slaRecordId, short rung, int elapsedMinutesAtFire,
                         Long notificationId) {
        this.slaRecordId = slaRecordId;
        this.rung = rung;
        this.elapsedMinutesAtFire = elapsedMinutesAtFire;
        this.notificationId = notificationId;
    }

    public Long getId() { return id; }
    public Long getSlaRecordId() { return slaRecordId; }
    public short getRung() { return rung; }
    public OffsetDateTime getFiredAt() { return firedAt; }
    public Long getNotificationId() { return notificationId; }
    public int getElapsedMinutesAtFire() { return elapsedMinutesAtFire; }
}
