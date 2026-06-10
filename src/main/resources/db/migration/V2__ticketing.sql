-- V2__ticketing.sql
-- Ticketing
-- Generated from docs/planning/04-DATABASE-SCHEMA.md at Phase 2.
-- DO NOT EDIT once applied: Flyway checksums this file.

CREATE TABLE ticket (
    id                  BIGSERIAL    PRIMARY KEY,
    tenant_id           BIGINT       NOT NULL,
    reference           VARCHAR(24)  NOT NULL,   -- TKT-10428; id never exposed
    subject             VARCHAR(200) NOT NULL
                        CONSTRAINT ck_ticket_subject CHECK (char_length(subject) BETWEEN 1 AND 200),
    body                TEXT         NOT NULL
                        CONSTRAINT ck_ticket_body CHECK (char_length(body) BETWEEN 1 AND 20000),
                        -- the cap is a token-cost DoS control, not a style rule
    status              VARCHAR(24)  NOT NULL DEFAULT 'OPEN'
                        CONSTRAINT ck_ticket_status CHECK (status IN
                          ('OPEN','TRIAGED','ASSIGNED','IN_PROGRESS','WAITING_ON_CUSTOMER',
                           'PENDING_THIRD_PARTY','RESOLVED','CLOSED')),
    priority            VARCHAR(12)  NOT NULL DEFAULT 'UNTRIAGED'
                        CONSTRAINT ck_ticket_priority CHECK (priority IN
                          ('UNTRIAGED','P1','P2','P3','P4')),
    category            VARCHAR(40)  NULL,
    requester_id        BIGINT       NOT NULL,
    assignee_id         BIGINT       NULL,
    team_id             BIGINT       NULL,
    reopen_count        INTEGER      NOT NULL DEFAULT 0
                        CONSTRAINT ck_ticket_reopen CHECK (reopen_count >= 0),
    first_responded_at  TIMESTAMPTZ  NULL,   -- written in the SAME txn as the first reply
    resolved_at         TIMESTAMPTZ  NULL,
    closed_at           TIMESTAMPTZ  NULL,
    embedding           vector(768)  NULL,   -- incident clustering
    search_tsv          tsvector     NULL,
    version             INTEGER      NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_ticket_reference UNIQUE (tenant_id, reference),
    CONSTRAINT fk_ticket_tenant    FOREIGN KEY (tenant_id)
        REFERENCES tenant(id)   ON DELETE RESTRICT,
    CONSTRAINT fk_ticket_requester FOREIGN KEY (requester_id)
        REFERENCES app_user(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ticket_assignee  FOREIGN KEY (assignee_id)
        REFERENCES app_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_ticket_team      FOREIGN KEY (team_id)
        REFERENCES team(id)     ON DELETE SET NULL
);
-- THE highest-traffic query in the application: the agent queue
CREATE INDEX idx_ticket_queue     ON ticket(tenant_id, status, priority, created_at DESC);
CREATE INDEX idx_ticket_assignee  ON ticket(tenant_id, assignee_id, status);
CREATE INDEX idx_ticket_team      ON ticket(tenant_id, team_id, status);
CREATE INDEX idx_ticket_requester ON ticket(tenant_id, requester_id, created_at DESC);
CREATE INDEX idx_ticket_search    ON ticket USING GIN (search_tsv);
CREATE INDEX idx_ticket_embedding ON ticket USING hnsw (embedding vector_cosine_ops);
-- correlation sweep touches only recent OPEN tickets
CREATE INDEX idx_ticket_recent    ON ticket(tenant_id, created_at)
    WHERE status NOT IN ('RESOLVED','CLOSED');

CREATE TABLE draft (   -- created early: ticket_message.from_draft_id references it
    id                 BIGSERIAL    PRIMARY KEY,
    ticket_id          BIGINT       NOT NULL,
    tenant_id          BIGINT       NOT NULL,
    prompt_version_id  BIGINT       NOT NULL,
    model_id           VARCHAR(80)  NOT NULL,
    requested_by       BIGINT       NOT NULL,
    coverage           NUMERIC(4,3) NULL
                       CONSTRAINT ck_draft_coverage CHECK (coverage BETWEEN 0 AND 1),
    status             VARCHAR(30)  NOT NULL DEFAULT 'PENDING'
                       CONSTRAINT ck_draft_status CHECK (status IN
                         ('PENDING','SHOWN','SUPPRESSED_LOW_COVERAGE',
                          'SUPPRESSED_NO_EVIDENCE','FAILED')),
    tokens_in          INTEGER      NOT NULL DEFAULT 0,
    tokens_out         INTEGER      NOT NULL DEFAULT 0,
    cost_micros        BIGINT       NOT NULL DEFAULT 0,   -- integer micro-units, never FLOAT
    latency_ms         INTEGER      NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_draft_ticket FOREIGN KEY (ticket_id)
        REFERENCES ticket(id)   ON DELETE CASCADE,
    CONSTRAINT fk_draft_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id)   ON DELETE RESTRICT,
    CONSTRAINT fk_draft_user   FOREIGN KEY (requested_by)
        REFERENCES app_user(id) ON DELETE RESTRICT
);
CREATE INDEX idx_draft_ticket  ON draft(ticket_id, created_at DESC);
CREATE INDEX idx_draft_quality ON draft(tenant_id, status, created_at);

CREATE TABLE ticket_message (
    id                BIGSERIAL   PRIMARY KEY,
    ticket_id         BIGINT      NOT NULL,
    tenant_id         BIGINT      NOT NULL,
    author_id         BIGINT      NOT NULL,
    body              TEXT        NOT NULL
                      CONSTRAINT ck_message_body CHECK (char_length(body) BETWEEN 1 AND 20000),
    visibility        VARCHAR(10) NOT NULL DEFAULT 'PUBLIC'
                      CONSTRAINT ck_message_visibility CHECK (visibility IN ('PUBLIC','INTERNAL')),
                      -- a bug here is a customer-visible leak: constrained in the DB
    is_first_response BOOLEAN     NOT NULL DEFAULT FALSE,
    from_draft_id     BIGINT      NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- no updated_at: messages are immutable
    CONSTRAINT fk_message_ticket FOREIGN KEY (ticket_id)
        REFERENCES ticket(id)   ON DELETE CASCADE,
    CONSTRAINT fk_message_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id)   ON DELETE RESTRICT,
    CONSTRAINT fk_message_author FOREIGN KEY (author_id)
        REFERENCES app_user(id) ON DELETE RESTRICT,
    CONSTRAINT fk_message_draft  FOREIGN KEY (from_draft_id)
        REFERENCES draft(id)    ON DELETE SET NULL
);
CREATE INDEX idx_message_thread ON ticket_message(ticket_id, created_at);
CREATE INDEX idx_message_author ON ticket_message(author_id);
CREATE UNIQUE INDEX idx_message_first_response
    ON ticket_message(ticket_id) WHERE is_first_response;

CREATE TABLE ticket_event (          -- APPEND-ONLY (trigger-enforced, V7)
    id          BIGSERIAL   PRIMARY KEY,
    ticket_id   BIGINT      NOT NULL,
    tenant_id   BIGINT      NOT NULL,
    actor_id    BIGINT      NULL,    -- NULL = the system did it
    event_type  VARCHAR(40) NOT NULL
                CONSTRAINT ck_event_type CHECK (event_type IN
                  ('CREATED','TRIAGED','ASSIGNED','STATUS_CHANGED','PRIORITY_CHANGED',
                   'MESSAGE_ADDED','SLA_STARTED','SLA_PAUSED','SLA_RESUMED','SLA_ESCALATED',
                   'SLA_BREACHED','SLA_MET','INCIDENT_LINKED','INCIDENT_DETACHED',
                   'RESOLVED','REOPENED','CLOSED')),
    from_value  VARCHAR(80) NULL,
    to_value    VARCHAR(80) NULL,
    payload     JSONB       NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_event_ticket FOREIGN KEY (ticket_id)
        REFERENCES ticket(id)   ON DELETE CASCADE,
    CONSTRAINT fk_event_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id)   ON DELETE RESTRICT,
    CONSTRAINT fk_event_actor  FOREIGN KEY (actor_id)
        REFERENCES app_user(id) ON DELETE SET NULL
);
CREATE INDEX idx_event_ticket ON ticket_event(ticket_id, occurred_at);
CREATE INDEX idx_event_audit  ON ticket_event(tenant_id, event_type, occurred_at DESC);

-- Deterministic entities extracted from ticket text. Feeds the correlation gate's
-- shared-entity boost (doc 12 T3/T6). Folded in at Phase 2 Task 2.
-- Deliberately NOT the LLM's extractedEntities: the gate must be reproducible, and
-- the whole argument of that feature is that a model does not decide an incident exists.
CREATE TABLE ticket_entity (
    id           BIGSERIAL   PRIMARY KEY,
    ticket_id    BIGINT      NOT NULL,
    tenant_id    BIGINT      NOT NULL,
    entity_type  VARCHAR(24) NOT NULL
                 CONSTRAINT ck_entity_type CHECK (entity_type IN
                   ('ERROR_CODE','SERVICE','REGION','PAYMENT_METHOD','APP_VERSION','HTTP_STATUS')),
    entity_value VARCHAR(80) NOT NULL,
    CONSTRAINT uq_ticket_entity UNIQUE (ticket_id, entity_type, entity_value),
    CONSTRAINT fk_te_ticket FOREIGN KEY (ticket_id)
        REFERENCES ticket(id) ON DELETE CASCADE,
    CONSTRAINT fk_te_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id) ON DELETE RESTRICT
);
-- "which other recent tickets mention payment-service?"
CREATE INDEX idx_ticket_entity_lookup ON ticket_entity(tenant_id, entity_type, entity_value);
CREATE INDEX idx_ticket_entity_ticket ON ticket_entity(ticket_id);

CREATE TABLE attachment (
    id                BIGSERIAL    PRIMARY KEY,
    message_id        BIGINT       NOT NULL,
    tenant_id         BIGINT       NOT NULL,
    storage_key       VARCHAR(255) NOT NULL,   -- generated UUID path, NOT user filename
    original_filename VARCHAR(255) NOT NULL,   -- display only
    mime_type         VARCHAR(100) NOT NULL,   -- verified against magic bytes
    byte_size         BIGINT       NOT NULL
                      CONSTRAINT ck_attachment_size CHECK (byte_size > 0 AND byte_size <= 10485760),
    content_sha256    CHAR(64)     NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_attachment_key UNIQUE (storage_key),
    CONSTRAINT fk_attachment_message FOREIGN KEY (message_id)
        REFERENCES ticket_message(id) ON DELETE CASCADE,
    CONSTRAINT fk_attachment_tenant  FOREIGN KEY (tenant_id)
        REFERENCES tenant(id)         ON DELETE RESTRICT
);
CREATE INDEX idx_attachment_message ON attachment(message_id);
