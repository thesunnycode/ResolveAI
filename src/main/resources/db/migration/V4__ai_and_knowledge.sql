-- V4__ai_and_knowledge.sql
-- AI and knowledge
-- Generated from docs/planning/04-DATABASE-SCHEMA.md at Phase 2.
-- DO NOT EDIT once applied: Flyway checksums this file.

CREATE TABLE prompt_version (        -- immutable rows
    id            BIGSERIAL   PRIMARY KEY,
    name          VARCHAR(60) NOT NULL,
    version       INTEGER     NOT NULL CONSTRAINT ck_prompt_version CHECK (version > 0),
    template      TEXT        NOT NULL,
    model_id      VARCHAR(80) NOT NULL,
    params        JSONB       NOT NULL DEFAULT '{}',
    output_schema JSONB       NULL,
    is_active     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_prompt_name_version UNIQUE (name, version)
);
CREATE UNIQUE INDEX uq_prompt_active ON prompt_version(name) WHERE is_active;

CREATE TABLE ai_analysis (
    id                BIGSERIAL    PRIMARY KEY,
    ticket_id         BIGINT       NOT NULL,
    tenant_id         BIGINT       NOT NULL,
    prompt_version_id BIGINT       NOT NULL,
    model_id          VARCHAR(80)  NOT NULL,   -- may differ after escalation/failover
    signals           JSONB        NOT NULL,   -- the TriageSignals object
    confidence        NUMERIC(4,3) NULL
                      CONSTRAINT ck_analysis_conf CHECK (confidence BETWEEN 0 AND 1),
    raw_response      JSONB        NULL,
    tokens_in         INTEGER      NOT NULL DEFAULT 0,
    tokens_out        INTEGER      NOT NULL DEFAULT 0,
    cost_micros       BIGINT       NOT NULL DEFAULT 0,
    latency_ms        INTEGER      NOT NULL DEFAULT 0,
    attempt           SMALLINT     NOT NULL DEFAULT 1,
    status            VARCHAR(20)  NOT NULL DEFAULT 'OK'
                      CONSTRAINT ck_analysis_status CHECK (status IN
                        ('OK','PARSE_FAILED','ESCALATED','FAILED','BUDGET_HELD')),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- idempotency: a redelivered triage event cannot duplicate the row
    CONSTRAINT uq_analysis_ticket_prompt UNIQUE (ticket_id, prompt_version_id, attempt),
    CONSTRAINT fk_analysis_ticket FOREIGN KEY (ticket_id)
        REFERENCES ticket(id)         ON DELETE CASCADE,
    CONSTRAINT fk_analysis_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id)         ON DELETE RESTRICT,
    CONSTRAINT fk_analysis_prompt FOREIGN KEY (prompt_version_id)
        REFERENCES prompt_version(id) ON DELETE RESTRICT
);
CREATE INDEX idx_analysis_ticket ON ai_analysis(ticket_id, created_at DESC);
CREATE INDEX idx_analysis_usage  ON ai_analysis(tenant_id, created_at);

CREATE TABLE priority_decision (
    id                BIGSERIAL   PRIMARY KEY,
    ticket_id         BIGINT      NOT NULL,
    tenant_id         BIGINT      NOT NULL,
    ai_analysis_id    BIGINT      NULL,      -- NULL when triage was unavailable
    policy_version    VARCHAR(30) NOT NULL,
    input_signals     JSONB       NOT NULL,  -- EVERY input, snapshotted
    computed_priority VARCHAR(12) NOT NULL
                      CONSTRAINT ck_decision_priority CHECK (computed_priority IN
                        ('UNTRIAGED','P1','P2','P3','P4')),
    rationale         JSONB       NOT NULL,  -- shown in the UI
    decided_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_decision_ticket   FOREIGN KEY (ticket_id)
        REFERENCES ticket(id)      ON DELETE CASCADE,
    CONSTRAINT fk_decision_tenant   FOREIGN KEY (tenant_id)
        REFERENCES tenant(id)      ON DELETE RESTRICT,
    CONSTRAINT fk_decision_analysis FOREIGN KEY (ai_analysis_id)
        REFERENCES ai_analysis(id) ON DELETE SET NULL
);
CREATE INDEX idx_priority_ticket ON priority_decision(ticket_id, decided_at DESC);

CREATE TABLE priority_override (
    id            BIGSERIAL    PRIMARY KEY,
    ticket_id     BIGINT       NOT NULL,
    tenant_id     BIGINT       NOT NULL,
    from_priority VARCHAR(12)  NOT NULL,
    to_priority   VARCHAR(12)  NOT NULL
                  CONSTRAINT ck_override_to CHECK (to_priority IN ('P1','P2','P3','P4')),
    reason        VARCHAR(500) NOT NULL,
    overridden_by BIGINT       NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_override_ticket FOREIGN KEY (ticket_id)
        REFERENCES ticket(id)   ON DELETE CASCADE,
    CONSTRAINT fk_override_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id)   ON DELETE RESTRICT,
    CONSTRAINT fk_override_user   FOREIGN KEY (overridden_by)
        REFERENCES app_user(id) ON DELETE RESTRICT
);
CREATE INDEX idx_override_ticket   ON priority_override(ticket_id);
CREATE INDEX idx_override_analysis ON priority_override(tenant_id, created_at DESC);

CREATE TABLE knowledge_document (
    id               BIGSERIAL    PRIMARY KEY,
    tenant_id        BIGINT       NOT NULL,
    source           VARCHAR(20)  NOT NULL
                     CONSTRAINT ck_kb_source CHECK (source IN
                       ('ARTICLE','RUNBOOK','RESOLVED_TICKET')),
    title            VARCHAR(255) NOT NULL,
    body             TEXT         NOT NULL,
    uri              VARCHAR(500) NULL,
    content_sha256   CHAR(64)     NOT NULL,
    kb_version       BIGINT       NOT NULL DEFAULT 1,  -- part of every retrieval cache key
    source_ticket_id BIGINT       NULL,
    indexed_at       TIMESTAMPTZ  NULL,
    deleted_at       TIMESTAMPTZ  NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_kb_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id) ON DELETE RESTRICT,
    CONSTRAINT fk_kb_ticket FOREIGN KEY (source_ticket_id)
        REFERENCES ticket(id) ON DELETE SET NULL
);
CREATE UNIQUE INDEX uq_kb_content
    ON knowledge_document(tenant_id, content_sha256) WHERE deleted_at IS NULL;
CREATE INDEX idx_kb_tenant  ON knowledge_document(tenant_id, source) WHERE deleted_at IS NULL;
CREATE INDEX idx_kb_pending ON knowledge_document(tenant_id)
    WHERE indexed_at IS NULL AND deleted_at IS NULL;

CREATE TABLE knowledge_chunk (
    id          BIGSERIAL   PRIMARY KEY,
    document_id BIGINT      NOT NULL,
    tenant_id   BIGINT      NOT NULL,   -- denormalised: pre-filter before the vector scan
    ordinal     INTEGER     NOT NULL,
    text        TEXT        NOT NULL,
    text_tsv    tsvector    NOT NULL,   -- lexical half
    embedding   vector(768) NOT NULL,   -- dense half
    token_count INTEGER     NOT NULL,
    char_start  INTEGER     NOT NULL,   -- so a citation can point at an exact span
    char_end    INTEGER     NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_chunk_ordinal UNIQUE (document_id, ordinal),
    CONSTRAINT ck_chunk_span    CHECK (char_end > char_start),
    CONSTRAINT fk_chunk_document FOREIGN KEY (document_id)
        REFERENCES knowledge_document(id) ON DELETE CASCADE,
    CONSTRAINT fk_chunk_tenant   FOREIGN KEY (tenant_id)
        REFERENCES tenant(id)             ON DELETE RESTRICT
);
CREATE INDEX idx_chunk_embedding ON knowledge_chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_chunk_tsv       ON knowledge_chunk USING GIN (text_tsv);
CREATE INDEX idx_chunk_tenant    ON knowledge_chunk(tenant_id);

CREATE TABLE draft_claim (
    id             BIGSERIAL   PRIMARY KEY,
    draft_id       BIGINT      NOT NULL,
    ordinal        INTEGER     NOT NULL,
    text           TEXT        NOT NULL,
    verdict        VARCHAR(30) NOT NULL DEFAULT 'PENDING'
                   CONSTRAINT ck_claim_verdict CHECK (verdict IN
                     ('PENDING','SUPPORTED','PARTIAL','NOT_SUPPORTED','FAILED_NUMERIC_CHECK')),
    verifier_model VARCHAR(80) NULL,   -- NULL = rejected by the free deterministic pre-filter
    kept           BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_claim_ordinal UNIQUE (draft_id, ordinal),
    CONSTRAINT fk_claim_draft FOREIGN KEY (draft_id)
        REFERENCES draft(id) ON DELETE CASCADE
);
CREATE INDEX idx_claim_verdict ON draft_claim(verdict);

CREATE TABLE draft_claim_citation (
    id             BIGSERIAL PRIMARY KEY,
    draft_claim_id BIGINT    NOT NULL,
    chunk_id       BIGINT    NOT NULL,
    char_start     INTEGER   NOT NULL,
    char_end       INTEGER   NOT NULL,
    CONSTRAINT uq_claim_citation UNIQUE (draft_claim_id, chunk_id, char_start),
    CONSTRAINT ck_citation_span CHECK (char_end > char_start),
    CONSTRAINT fk_citation_claim FOREIGN KEY (draft_claim_id)
        REFERENCES draft_claim(id)     ON DELETE CASCADE,
    CONSTRAINT fk_citation_chunk FOREIGN KEY (chunk_id)
        REFERENCES knowledge_chunk(id) ON DELETE RESTRICT
);
CREATE INDEX idx_citation_chunk ON draft_claim_citation(chunk_id);

CREATE TABLE agent_draft_action (
    id            BIGSERIAL   PRIMARY KEY,
    draft_id      BIGINT      NOT NULL,
    agent_id      BIGINT      NOT NULL,
    action        VARCHAR(20) NOT NULL
                  CONSTRAINT ck_draft_action CHECK (action IN
                    ('SENT_AS_IS','EDITED','DISCARDED')),
    edit_distance INTEGER     NULL CONSTRAINT ck_edit_distance CHECK (edit_distance >= 0),
    final_text    TEXT        NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_draft_action UNIQUE (draft_id),
    CONSTRAINT fk_action_draft FOREIGN KEY (draft_id)
        REFERENCES draft(id)    ON DELETE CASCADE,
    CONSTRAINT fk_action_agent FOREIGN KEY (agent_id)
        REFERENCES app_user(id) ON DELETE RESTRICT
);
CREATE INDEX idx_draft_action_metric ON agent_draft_action(action, created_at);
