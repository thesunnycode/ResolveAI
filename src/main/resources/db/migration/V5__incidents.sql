-- V5__incidents.sql
-- Incidents
-- Generated from docs/planning/04-DATABASE-SCHEMA.md at Phase 2.
-- DO NOT EDIT once applied: Flyway checksums this file.

CREATE TABLE incident (
    id                        BIGSERIAL     PRIMARY KEY,
    tenant_id                 BIGINT        NOT NULL,
    reference                 VARCHAR(24)   NOT NULL,
    title                     VARCHAR(200)  NOT NULL,
    summary                   TEXT          NULL,
    generated_by_model        VARCHAR(80)   NULL,   -- NULL = templated title
    prompt_version_id         BIGINT        NULL,
    status                    VARCHAR(20)   NOT NULL DEFAULT 'PROPOSED'
                              CONSTRAINT ck_incident_status CHECK (status IN
                                ('PROPOSED','CONFIRMED','REJECTED','MITIGATED','RESOLVED')),
    detection_method          VARCHAR(20)   NOT NULL DEFAULT 'CLUSTER'
                              CONSTRAINT ck_incident_method CHECK (detection_method IN
                                ('CLUSTER','MANUAL')),
    cluster_size_at_detection INTEGER       NOT NULL DEFAULT 0,
    arrival_rate_multiple     NUMERIC(6,2)  NULL,   -- the gate's evidence, stored
    first_ticket_at           TIMESTAMPTZ   NOT NULL,
    detected_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    confirmed_by              BIGINT        NULL,
    confirmed_at              TIMESTAMPTZ   NULL,
    rejected_reason           VARCHAR(500)  NULL,
    resolved_at               TIMESTAMPTZ   NULL,
    version                   INTEGER       NOT NULL DEFAULT 0,
    created_at                TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_incident_reference UNIQUE (tenant_id, reference),
    CONSTRAINT fk_incident_tenant  FOREIGN KEY (tenant_id)
        REFERENCES tenant(id)         ON DELETE RESTRICT,
    CONSTRAINT fk_incident_prompt  FOREIGN KEY (prompt_version_id)
        REFERENCES prompt_version(id) ON DELETE RESTRICT,
    CONSTRAINT fk_incident_confirm FOREIGN KEY (confirmed_by)
        REFERENCES app_user(id)       ON DELETE SET NULL
);
CREATE INDEX idx_incident_board ON incident(tenant_id, status, detected_at DESC);
CREATE INDEX idx_incident_open  ON incident(tenant_id)
    WHERE status IN ('PROPOSED','CONFIRMED','MITIGATED');

CREATE TABLE incident_ticket (
    id              BIGSERIAL    PRIMARY KEY,
    incident_id     BIGINT       NOT NULL,
    ticket_id       BIGINT       NOT NULL,
    link_confidence NUMERIC(4,3) NULL,
    linked_by       BIGINT       NULL,     -- NULL = automatic
    linked_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    detached_at     TIMESTAMPTZ  NULL,     -- soft detach; a wrong link is useful history
    detached_by     BIGINT       NULL,
    CONSTRAINT fk_it_incident FOREIGN KEY (incident_id)
        REFERENCES incident(id) ON DELETE CASCADE,
    CONSTRAINT fk_it_ticket   FOREIGN KEY (ticket_id)
        REFERENCES ticket(id)   ON DELETE CASCADE,
    CONSTRAINT fk_it_linker   FOREIGN KEY (linked_by)
        REFERENCES app_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_it_detacher FOREIGN KEY (detached_by)
        REFERENCES app_user(id) ON DELETE SET NULL
);
-- A ticket belongs to at most ONE live incident. Enforced, not hoped for.
CREATE UNIQUE INDEX uq_incident_ticket_live
    ON incident_ticket(ticket_id) WHERE detached_at IS NULL;
CREATE INDEX idx_incident_tickets
    ON incident_ticket(incident_id) WHERE detached_at IS NULL;

CREATE TABLE incident_update (
    id           BIGSERIAL   PRIMARY KEY,
    incident_id  BIGINT      NOT NULL,
    author_id    BIGINT      NOT NULL,
    body         TEXT        NOT NULL,
    visibility   VARCHAR(10) NOT NULL DEFAULT 'INTERNAL'
                 CONSTRAINT ck_iu_visibility CHECK (visibility IN ('PUBLIC','INTERNAL')),
    published_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_iu_incident FOREIGN KEY (incident_id)
        REFERENCES incident(id) ON DELETE CASCADE,
    CONSTRAINT fk_iu_author   FOREIGN KEY (author_id)
        REFERENCES app_user(id) ON DELETE RESTRICT
);
CREATE INDEX idx_update_incident ON incident_update(incident_id, published_at);

CREATE TABLE incident_update_delivery (
    id                 BIGSERIAL    PRIMARY KEY,
    incident_update_id BIGINT       NOT NULL,
    ticket_id          BIGINT       NOT NULL,
    status             VARCHAR(12)  NOT NULL DEFAULT 'PENDING'
                       CONSTRAINT ck_delivery_status CHECK (status IN
                         ('PENDING','SENT','FAILED')),
    attempts           SMALLINT     NOT NULL DEFAULT 0,
    last_error         VARCHAR(500) NULL,
    delivered_at       TIMESTAMPTZ  NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- fan-out idempotency: a redelivered event will not double-notify a customer
    CONSTRAINT uq_delivery UNIQUE (incident_update_id, ticket_id),
    CONSTRAINT fk_delivery_update FOREIGN KEY (incident_update_id)
        REFERENCES incident_update(id) ON DELETE CASCADE,
    CONSTRAINT fk_delivery_ticket FOREIGN KEY (ticket_id)
        REFERENCES ticket(id)          ON DELETE CASCADE
);
CREATE INDEX idx_delivery_pending
    ON incident_update_delivery(status, created_at) WHERE status = 'PENDING';
