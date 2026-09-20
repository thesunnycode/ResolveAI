-- V1__extensions_and_tenancy.sql
-- Extensions and tenancy
-- Generated from docs/planning/04-DATABASE-SCHEMA.md at Phase 2.
-- DO NOT EDIT once applied: Flyway checksums this file.

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE tenant (
    id          BIGSERIAL     PRIMARY KEY,
    name        VARCHAR(150)  NOT NULL,
    slug        VARCHAR(60)   NOT NULL,
    plan_tier   VARCHAR(20)   NOT NULL DEFAULT 'FREE'
                CONSTRAINT ck_tenant_plan CHECK (plan_tier IN ('FREE','PRO','ENTERPRISE')),
    is_active   BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_tenant_slug UNIQUE (slug)
);

CREATE TABLE team (
    id          BIGSERIAL     PRIMARY KEY,
    tenant_id   BIGINT        NOT NULL,
    name        VARCHAR(80)   NOT NULL,
    skills      TEXT[]        NOT NULL DEFAULT '{}',   -- real array, never a CSV string
    is_default  BOOLEAN       NOT NULL DEFAULT FALSE,
    deleted_at  TIMESTAMPTZ   NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_team_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id) ON DELETE RESTRICT
);
CREATE UNIQUE INDEX uq_team_tenant_name ON team(tenant_id, name) WHERE deleted_at IS NULL;
CREATE INDEX idx_team_skills  ON team USING GIN (skills);   -- skills && ARRAY['PAYMENT']
CREATE INDEX idx_team_default ON team(tenant_id) WHERE is_default;

CREATE TABLE app_user (
    id             BIGSERIAL    PRIMARY KEY,
    tenant_id      BIGINT       NOT NULL,
    email          VARCHAR(255) NOT NULL,
    password_hash  VARCHAR(72)  NOT NULL,   -- BCrypt cost 12; never plaintext
    full_name      VARCHAR(120) NOT NULL,
    role           VARCHAR(20)  NOT NULL DEFAULT 'CUSTOMER'
                   CONSTRAINT ck_user_role CHECK (role IN ('CUSTOMER','AGENT','TEAM_LEAD','ADMIN')),
    team_id        BIGINT       NULL,
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    last_login_at  TIMESTAMPTZ  NULL,
    deleted_at     TIMESTAMPTZ  NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_user_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id) ON DELETE RESTRICT,
    CONSTRAINT fk_user_team   FOREIGN KEY (team_id)
        REFERENCES team(id)   ON DELETE SET NULL
);
-- Partial unique: email reusable after soft delete
CREATE UNIQUE INDEX uq_user_tenant_email ON app_user(tenant_id, email) WHERE deleted_at IS NULL;
CREATE INDEX idx_user_tenant_role ON app_user(tenant_id, role);
CREATE INDEX idx_user_team        ON app_user(team_id);

CREATE TABLE agent_profile (
    id              BIGSERIAL   PRIMARY KEY,
    user_id         BIGINT      NOT NULL,
    tenant_id       BIGINT      NOT NULL,
    max_concurrent  INTEGER     NOT NULL DEFAULT 15
                    CONSTRAINT ck_agent_max CHECK (max_concurrent > 0),
    open_count      INTEGER     NOT NULL DEFAULT 0     -- the contended column
                    CONSTRAINT ck_agent_open CHECK (open_count >= 0),
    is_available    BOOLEAN     NOT NULL DEFAULT TRUE,
    shift_start     TIME        NULL,
    shift_end       TIME        NULL,
    version         INTEGER     NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_agent_profile_user UNIQUE (user_id),
    CONSTRAINT fk_agent_user   FOREIGN KEY (user_id)
        REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id)   ON DELETE RESTRICT
);
-- Serves: WHERE tenant_id=? AND is_available ORDER BY open_count
--         LIMIT 1 FOR UPDATE SKIP LOCKED
CREATE INDEX idx_agent_routing ON agent_profile(tenant_id, is_available, open_count);

-- Generates TKT-10428 / INC-204. Folded in at Phase 2 Task 2: ticket.reference is
-- NOT NULL and the schema was incomplete without it (found in doc 10 T2).
-- Row lock serialises creation per tenant; at ~1 write/sec that is irrelevant, and
-- the escape hatch if it ever is not is hi/lo allocation (claim 100, hand out from
-- memory, accept gaps).
CREATE TABLE tenant_sequence (
    tenant_id   BIGINT      NOT NULL,
    entity_type VARCHAR(20) NOT NULL
                CONSTRAINT ck_tenseq_type CHECK (entity_type IN ('TICKET','INCIDENT')),
    next_value  BIGINT      NOT NULL DEFAULT 1000,
    PRIMARY KEY (tenant_id, entity_type),
    CONSTRAINT fk_tenseq_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id) ON DELETE CASCADE
);

CREATE TABLE refresh_token (
    id          BIGSERIAL   PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    token_hash  CHAR(64)    NOT NULL,   -- SHA-256; raw token never stored
    family_id   UUID        NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ NULL,       -- non-null + presented again = reuse attack
    revoked_at  TIMESTAMPTZ NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_refresh_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_user FOREIGN KEY (user_id)
        REFERENCES app_user(id) ON DELETE CASCADE
);
CREATE INDEX idx_refresh_family ON refresh_token(family_id);
CREATE INDEX idx_refresh_expiry ON refresh_token(expires_at);
