-- V17 — team invites.
--
-- Lets an Admin bring an Agent or Team Lead into their own tenant by email instead of the
-- only prior option (a local dev seeder). tenant_id is a plain column, not filtered by
-- Hibernate's @TenantId discriminator: accept-invite resolves a tenant from the token alone,
-- before any tenant context exists, the same reason `tenant` itself carries no discriminator.
--
-- No app_user row exists for the invitee until accepted_at is set - an invite is a promise,
-- not a half-created account with no password.

CREATE TABLE invite (
    id                 BIGSERIAL     PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL,
    email              VARCHAR(255)  NOT NULL,
    role               VARCHAR(20)   NOT NULL
                       CONSTRAINT ck_invite_role CHECK (role IN ('AGENT', 'TEAM_LEAD')),
    team_id            BIGINT        NOT NULL,
    invited_by_user_id BIGINT        NOT NULL,
    token              VARCHAR(64)   NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    expires_at         TIMESTAMPTZ   NOT NULL,
    accepted_at        TIMESTAMPTZ   NULL,
    CONSTRAINT fk_invite_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id) ON DELETE RESTRICT,
    CONSTRAINT fk_invite_team FOREIGN KEY (team_id)
        REFERENCES team(id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uq_invite_token ON invite(token);
CREATE INDEX idx_invite_tenant ON invite(tenant_id, created_at DESC);
