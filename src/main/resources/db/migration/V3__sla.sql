-- V3__sla.sql
-- SLA
-- Generated from docs/planning/04-DATABASE-SCHEMA.md at Phase 2.
-- DO NOT EDIT once applied: Flyway checksums this file.

CREATE TABLE business_calendar (
    id          BIGSERIAL   PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL,
    timezone    VARCHAR(64) NOT NULL DEFAULT 'Asia/Kolkata',  -- IANA name, never a UTC offset
    working_days SMALLINT[] NOT NULL DEFAULT '{1,2,3,4,5}',   -- ISO-8601, Monday = 1
    day_start   TIME        NOT NULL DEFAULT '09:00',
    day_end     TIME        NOT NULL DEFAULT '18:00',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_calendar_tenant UNIQUE (tenant_id),
    CONSTRAINT ck_calendar_hours  CHECK (day_end > day_start),
    CONSTRAINT fk_calendar_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id) ON DELETE RESTRICT
);

CREATE TABLE business_holiday (
    id           BIGSERIAL   PRIMARY KEY,
    calendar_id  BIGINT      NOT NULL,
    holiday_date DATE        NOT NULL,   -- a calendar day, not an instant
    name         VARCHAR(80) NOT NULL,
    CONSTRAINT uq_holiday UNIQUE (calendar_id, holiday_date),
    CONSTRAINT fk_holiday_calendar FOREIGN KEY (calendar_id)
        REFERENCES business_calendar(id) ON DELETE CASCADE
);

CREATE TABLE sla_policy (
    id                     BIGSERIAL   PRIMARY KEY,
    tenant_id              BIGINT      NOT NULL,
    priority               VARCHAR(12) NOT NULL
                           CONSTRAINT ck_slapolicy_priority CHECK (priority IN ('P1','P2','P3','P4')),
    plan_tier              VARCHAR(20) NOT NULL
                           CONSTRAINT ck_slapolicy_plan CHECK (plan_tier IN ('FREE','PRO','ENTERPRISE')),
    first_response_minutes INTEGER     NOT NULL
                           CONSTRAINT ck_slapolicy_fr CHECK (first_response_minutes > 0),
    resolution_minutes     INTEGER     NOT NULL
                           CONSTRAINT ck_slapolicy_res CHECK (resolution_minutes > 0),
    escalation_rungs       SMALLINT[]  NOT NULL DEFAULT '{50,75,90,100}',
    effective_from         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    effective_to           TIMESTAMPTZ NULL,           -- NULL = currently in force
    version_label          VARCHAR(30) NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_slapolicy_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id) ON DELETE RESTRICT
);
-- exactly one live policy per (tenant, priority, plan) — overlaps are impossible
CREATE UNIQUE INDEX uq_sla_policy_live
    ON sla_policy(tenant_id, priority, plan_tier) WHERE effective_to IS NULL;
CREATE INDEX idx_sla_policy_lookup
    ON sla_policy(tenant_id, priority, plan_tier, effective_from DESC);

CREATE TABLE sla_record (
    id               BIGSERIAL   PRIMARY KEY,
    ticket_id        BIGINT      NOT NULL,
    tenant_id        BIGINT      NOT NULL,
    sla_policy_id    BIGINT      NOT NULL,
    policy_version   VARCHAR(30) NOT NULL,   -- snapshot: the policy row may be superseded
    kind             VARCHAR(20) NOT NULL
                     CONSTRAINT ck_sla_kind CHECK (kind IN ('FIRST_RESPONSE','RESOLUTION')),
    target_minutes   INTEGER     NOT NULL
                     CONSTRAINT ck_sla_target CHECK (target_minutes > 0),
    state            VARCHAR(12) NOT NULL DEFAULT 'RUNNING'
                     CONSTRAINT ck_sla_state CHECK (state IN
                       ('RUNNING','PAUSED','MET','BREACHED','CANCELLED')),
    next_deadline_at TIMESTAMPTZ NULL,       -- ABSOLUTE instant; NULL while PAUSED
    next_rung        SMALLINT    NULL DEFAULT 50,
    met_at           TIMESTAMPTZ NULL,
    breached_at      TIMESTAMPTZ NULL,
    version          INTEGER     NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_sla_ticket FOREIGN KEY (ticket_id)
        REFERENCES ticket(id)     ON DELETE CASCADE,
    CONSTRAINT fk_sla_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id)     ON DELETE RESTRICT,
    CONSTRAINT fk_sla_policy FOREIGN KEY (sla_policy_id)
        REFERENCES sla_policy(id) ON DELETE RESTRICT
);
-- THE most important index in the database.
-- Poller cost is proportional to DUE records, not OPEN ones.
CREATE INDEX idx_sla_poller ON sla_record(state, next_deadline_at) WHERE state = 'RUNNING';
CREATE UNIQUE INDEX uq_sla_ticket_kind
    ON sla_record(ticket_id, kind) WHERE state <> 'CANCELLED';
CREATE INDEX idx_sla_ticket  ON sla_record(ticket_id);
CREATE INDEX idx_sla_at_risk ON sla_record(tenant_id, state, next_deadline_at);

CREATE TABLE sla_clock_segment (        -- APPEND-ONLY. There is no elapsed_minutes column.
    id            BIGSERIAL   PRIMARY KEY,
    sla_record_id BIGINT      NOT NULL,
    state         VARCHAR(10) NOT NULL
                  CONSTRAINT ck_segment_state CHECK (state IN ('RUNNING','PAUSED')),
    started_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ended_at      TIMESTAMPTZ NULL,     -- NULL = the currently open segment
    pause_reason  VARCHAR(40) NULL
                  CONSTRAINT ck_segment_reason CHECK (pause_reason IS NULL OR pause_reason IN
                    ('WAITING_ON_CUSTOMER','PENDING_THIRD_PARTY','INCIDENT_LINKED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_segment_order CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT fk_segment_record FOREIGN KEY (sla_record_id)
        REFERENCES sla_record(id) ON DELETE CASCADE
);
-- Exactly one open segment per record. Two concurrent pauses cannot both succeed.
CREATE UNIQUE INDEX uq_segment_open
    ON sla_clock_segment(sla_record_id) WHERE ended_at IS NULL;
CREATE INDEX idx_segment_record ON sla_clock_segment(sla_record_id, started_at);

CREATE TABLE sla_escalation (
    id                       BIGSERIAL   PRIMARY KEY,
    sla_record_id            BIGINT      NOT NULL,
    rung                     SMALLINT    NOT NULL
                             CONSTRAINT ck_escalation_rung CHECK (rung IN (50,75,90,100)),
    fired_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    notification_id          BIGINT      NULL,
    elapsed_minutes_at_fire  INTEGER     NOT NULL,
    -- Turns an at-least-once poller into an exactly-once effect.
    CONSTRAINT uq_escalation_rung UNIQUE (sla_record_id, rung),
    CONSTRAINT fk_escalation_record FOREIGN KEY (sla_record_id)
        REFERENCES sla_record(id) ON DELETE CASCADE
);
