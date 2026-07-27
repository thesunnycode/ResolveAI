package com.resolveai.ticketing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/**
 * A file attached to a message.
 *
 * <p><b>{@code storageKey} is a generated UUID path and never the user's filename.</b> The
 * original name is kept separately, for display only. Using it as the object key would put
 * attacker-chosen text into a storage path — {@code ../}, a null byte, a name that collides
 * with another tenant's file — and the display name is the only thing anyone actually
 * wanted it for.
 *
 * <p>{@code contentSha256} is {@code CHAR(64)}, so it needs
 * {@code @JdbcTypeCode(SqlTypes.CHAR)}: without it Hibernate binds {@code varchar} and
 * {@code ddl-auto: validate} refuses to start. The same trap caught
 * {@code RefreshToken.tokenHash} in Phase 4.
 *
 * <p>The entity exists in Phase 5; the upload endpoints behind it are Task 17, which the
 * plan marks cuttable.
 */
@Entity
@Table(name = "attachment")
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Raw id rather than a {@code @ManyToOne}: an attachment is uploaded <i>before</i> the
     * message that carries it exists, so the association is set after the fact and a managed
     * reference would have nothing to point at during the pending window.
     */
    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "storage_key", nullable = false, length = 255, updatable = false)
    private String storageKey;

    @Column(name = "original_filename", nullable = false, length = 255, updatable = false)
    private String originalFilename;

    /** Verified against the object's actual magic bytes, not trusted from the upload header. */
    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_sha256", nullable = false, length = 64)
    private String contentSha256;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Attachment() {
        // JPA
    }

    public Attachment(Long messageId, String storageKey, String originalFilename,
                      String mimeType, long byteSize, String contentSha256) {
        this.messageId = messageId;
        this.storageKey = storageKey;
        this.originalFilename = originalFilename;
        this.mimeType = mimeType;
        this.byteSize = byteSize;
        this.contentSha256 = contentSha256;
    }

    public Long getId() { return id; }
    public Long getMessageId() { return messageId; }
    public Long getTenantId() { return tenantId; }
    public String getStorageKey() { return storageKey; }
    public String getOriginalFilename() { return originalFilename; }
    public String getMimeType() { return mimeType; }
    public long getByteSize() { return byteSize; }
    public String getContentSha256() { return contentSha256; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setMessageId(Long messageId) { this.messageId = messageId; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
}
