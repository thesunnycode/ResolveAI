-- V6__platform_and_eval.sql
-- Platform and evaluation
-- Generated from docs/planning/04-DATABASE-SCHEMA.md at Phase 2.
-- DO NOT EDIT once applied: Flyway checksums this file.

CREATE TABLE outbox_event (
    id              BIGSERIAL     PRIMARY KEY,
    tenant_id       BIGINT        NULL,
    aggregate_type  VARCHAR(40)   NOT NULL,
    aggregate_id    BIGINT        NOT NULL,   -- deliberately NOT a FK: generic queue
    event_type      VARCHAR(50)   NOT NULL,
    payload         JSONB         NOT NULL,
    status          VARCHAR(12)   NOT NULL DEFAULT 'PENDING'
                    CONSTRAINT ck_outbox_status CHECK (status IN
                      ('PENDING','IN_FLIGHT','DONE','DEAD')),
    attempts        SMALLINT      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    locked_until    TIMESTAMPTZ   NULL,       -- visibility timeout
    last_error      VARCHAR(1000) NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMPTZ   NULL,
    CONSTRAINT fk_outbox_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id) ON DELETE SET NULL
);
-- The single highest-frequency query in the system (6 workers × 1/sec).
-- The partial clause keeps the index tiny as DONE rows accumulate.
CREATE INDEX idx_outbox_claim  ON outbox_event(event_type, next_attempt_at)
    WHERE status = 'PENDING';
CREATE INDEX idx_outbox_reaper ON outbox_event(locked_until) WHERE status = 'IN_FLIGHT';
CREATE INDEX idx_outbox_prune  ON outbox_event(created_at)   WHERE status = 'DONE';
CREATE INDEX idx_outbox_dlq    ON outbox_event(tenant_id)    WHERE status = 'DEAD';

CREATE TABLE idempotency_record (
    id              BIGSERIAL    PRIMARY KEY,
    idem_key        VARCHAR(128) NOT NULL,
    tenant_id       BIGINT       NOT NULL,
    endpoint        VARCHAR(160) NOT NULL,
    request_hash    CHAR(64)     NOT NULL,   -- same key + different body = 422
    response_status INTEGER      NOT NULL,
    response_body   JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW() + INTERVAL '24 hours',
    CONSTRAINT uq_idem UNIQUE (tenant_id, idem_key, endpoint),
    CONSTRAINT fk_idem_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id) ON DELETE CASCADE
);
CREATE INDEX idx_idem_expiry ON idempotency_record(expires_at);

CREATE TABLE tenant_ai_policy (
    tenant_id                  BIGINT      PRIMARY KEY,
    external_model_allowed     BOOLEAN     NOT NULL DEFAULT TRUE,
    allowed_providers          TEXT[]      NOT NULL DEFAULT '{}',
    pii_redaction_required     BOOLEAN     NOT NULL DEFAULT TRUE,
    monthly_budget_micros      BIGINT      NOT NULL DEFAULT 5000000
                               CONSTRAINT ck_policy_budget CHECK (monthly_budget_micros >= 0),
    current_month_spend_micros BIGINT      NOT NULL DEFAULT 0,
    retention_days             INTEGER     NOT NULL DEFAULT 365
                               CONSTRAINT ck_policy_retention CHECK (retention_days > 0),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_aipolicy_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id) ON DELETE CASCADE
);

CREATE TABLE pii_redaction_map (
    id              BIGSERIAL   PRIMARY KEY,
    tenant_id       BIGINT      NOT NULL,
    ticket_id       BIGINT      NOT NULL,
    placeholder     VARCHAR(40) NOT NULL,
    pii_type        VARCHAR(20) NOT NULL
                    CONSTRAINT ck_pii_type CHECK (pii_type IN
                      ('PERSON','EMAIL','PHONE','CARD','GOV_ID','IP','ORDER_REF')),
    encrypted_value BYTEA       NOT NULL,   -- AES-GCM; never transmitted, never logged
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_pii_placeholder UNIQUE (ticket_id, placeholder),
    CONSTRAINT fk_pii_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id) ON DELETE CASCADE,
    CONSTRAINT fk_pii_ticket FOREIGN KEY (ticket_id)
        REFERENCES ticket(id) ON DELETE CASCADE
);

CREATE TABLE notification (
    id           BIGSERIAL     PRIMARY KEY,
    tenant_id    BIGINT        NOT NULL,
    recipient_id BIGINT        NOT NULL,
    kind         VARCHAR(40)   NOT NULL
                 CONSTRAINT ck_notification_kind CHECK (kind IN
                   ('SLA_ESCALATION','SLA_BREACH','TICKET_ASSIGNED','INCIDENT_PROPOSED',
                    'INCIDENT_UPDATE','BUDGET_EXHAUSTED','TRIAGE_FAILED')),
    title        VARCHAR(200)  NOT NULL,
    body         VARCHAR(1000) NULL,
    link_url     VARCHAR(300)  NULL,
    read_at      TIMESTAMPTZ   NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_notification_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id)   ON DELETE RESTRICT,
    CONSTRAINT fk_notification_user   FOREIGN KEY (recipient_id)
        REFERENCES app_user(id) ON DELETE CASCADE
);
CREATE INDEX idx_notification_unread
    ON notification(recipient_id, created_at DESC) WHERE read_at IS NULL;

ALTER TABLE sla_escalation
    ADD CONSTRAINT fk_escalation_notification FOREIGN KEY (notification_id)
        REFERENCES notification(id) ON DELETE SET NULL;

CREATE TABLE eval_case (
    id            BIGSERIAL    PRIMARY KEY,
    suite         VARCHAR(40)  NOT NULL
                  CONSTRAINT ck_eval_suite CHECK (suite IN
                    ('CLASSIFICATION','RETRIEVAL','GROUNDING','REFUSAL','INCIDENT')),
    name          VARCHAR(120) NOT NULL,
    input_fixture JSONB        NOT NULL,
    expected      JSONB        NOT NULL,
    source        VARCHAR(20)  NOT NULL DEFAULT 'HAND_LABELLED'
                  CONSTRAINT ck_eval_source CHECK (source IN
                    ('HAND_LABELLED','AGENT_OVERRIDE','INCIDENT_CONFIRM')),
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_eval_case UNIQUE (suite, name)
);
CREATE INDEX idx_eval_active ON eval_case(suite) WHERE is_active;

CREATE TABLE eval_run (
    id                BIGSERIAL   PRIMARY KEY,
    suite             VARCHAR(40) NOT NULL,
    prompt_version_id BIGINT      NULL,
    model_id          VARCHAR(80) NOT NULL,
    git_sha           CHAR(40)    NULL,
    metrics           JSONB       NOT NULL,
    passed            BOOLEAN     NOT NULL,   -- the CI gate reads this one column
    started_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at       TIMESTAMPTZ NULL,
    CONSTRAINT fk_evalrun_prompt FOREIGN KEY (prompt_version_id)
        REFERENCES prompt_version(id) ON DELETE RESTRICT
);
CREATE INDEX idx_eval_run_suite ON eval_run(suite, started_at DESC);

CREATE TABLE eval_result (
    id           BIGSERIAL PRIMARY KEY,
    eval_run_id  BIGINT      NOT NULL,
    eval_case_id BIGINT      NOT NULL,
    metric       VARCHAR(40) NOT NULL,
    expected     JSONB       NOT NULL,
    actual       JSONB       NOT NULL,
    passed       BOOLEAN     NOT NULL,
    CONSTRAINT fk_evalresult_run  FOREIGN KEY (eval_run_id)
        REFERENCES eval_run(id)  ON DELETE CASCADE,
    CONSTRAINT fk_evalresult_case FOREIGN KEY (eval_case_id)
        REFERENCES eval_case(id) ON DELETE RESTRICT
);
CREATE INDEX idx_eval_result_run ON eval_result(eval_run_id, passed);
