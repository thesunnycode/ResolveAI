-- V18 — self-service password reset.
--
-- tenant_id is a plain column, not filtered by Hibernate's @TenantId discriminator: the
-- reset-password page resolves a tenant from the token alone, before any tenant context
-- exists, the same reason invite.tenant_id carries no discriminator either.

CREATE TABLE password_reset_token (
    id         BIGSERIAL     PRIMARY KEY,
    tenant_id  BIGINT        NOT NULL,
    user_id    BIGINT        NOT NULL,
    token      VARCHAR(64)   NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ   NOT NULL,
    used_at    TIMESTAMPTZ   NULL,
    CONSTRAINT fk_password_reset_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uq_password_reset_token ON password_reset_token(token);
CREATE INDEX idx_password_reset_user ON password_reset_token(user_id, created_at DESC);
