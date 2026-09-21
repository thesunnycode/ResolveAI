package com.resolveai.ticketing.domain;

import com.resolveai.iam.domain.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;

/**
 * One message on a ticket thread — a customer's report, an agent's reply, or an internal
 * note.
 *
 * <p><b>Immutable once written, and the schema agrees:</b> {@code ticket_message} has no
 * {@code updated_at} column. An edited support thread is not an audit trail. There are
 * therefore no setters except the one for {@code isFirstResponse}, which is set exactly once
 * inside the transaction that inserts the row.
 *
 * <p>{@code idx_message_first_response} is a <b>partial</b> unique index —
 * {@code UNIQUE (ticket_id) WHERE is_first_response} — so two concurrent replies cannot both
 * claim the first response. The loser gets a constraint violation rather than a second row,
 * which the service catches and re-reads.
 */
@Entity
@Table(name = "ticket_message")
public class TicketMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false, updatable = false)
    private Ticket ticket;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false, updatable = false)
    private AppUser author;

    @Column(nullable = false, columnDefinition = "text", updatable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private Visibility visibility = Visibility.PUBLIC;

    @Column(name = "is_first_response", nullable = false)
    private boolean firstResponse;

    /**
     * The AI draft this reply came from, when it came from one.
     *
     * <p>Stored as a raw id rather than a {@code @ManyToOne} because {@code draft} belongs to
     * Phase 7 and has no entity yet. The column and its foreign key already exist, so the
     * value is accepted and persisted now; validating that it is {@code SHOWN} and belongs
     * to this ticket is Phase 7's job.
     */
    @Column(name = "from_draft_id", updatable = false)
    private Long fromDraftId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected TicketMessage() {
        // JPA
    }

    public TicketMessage(Ticket ticket, AppUser author, String body, Visibility visibility,
                         boolean firstResponse, Long fromDraftId) {
        this.ticket = ticket;
        this.author = author;
        this.body = body;
        this.visibility = visibility;
        this.firstResponse = firstResponse;
        this.fromDraftId = fromDraftId;
    }

    public Long getId() { return id; }
    public Ticket getTicket() { return ticket; }
    public Long getTenantId() { return tenantId; }
    public AppUser getAuthor() { return author; }
    public String getBody() { return body; }
    public Visibility getVisibility() { return visibility; }
    public boolean isFirstResponse() { return firstResponse; }
    public Long getFromDraftId() { return fromDraftId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public boolean isPublic() {
        return visibility == Visibility.PUBLIC;
    }
}
