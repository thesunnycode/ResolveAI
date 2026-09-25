-- V16 — first-party product analytics (onboarding audit, "Analytics gaps").
--
-- The funnel from the login page to first value could not be measured: there was no
-- product analytics at all. This is the smallest thing that answers the audit's open
-- questions (which demo role visitors pick, how many reach a shown draft, how heavily
-- agents edit drafts) without a third-party script or a consent banner.
--
-- tenant_id / user_id are NULLABLE and deliberately NOT foreign keys:
--   * events arrive from the login page, before anyone has a tenant or a user;
--   * an analytics row must never block deleting or retiring what it describes.
-- Rows carry no free text from users - `props` is a small, allow-listed JSON object
-- written by the SPA (see ProductEventController) - so there is no PII here to redact.

CREATE TABLE product_event (
    id           BIGSERIAL    PRIMARY KEY,
    name         VARCHAR(64)  NOT NULL
                 CONSTRAINT ck_product_event_name CHECK (name ~ '^[a-z][a-z0-9_]{1,63}$'),
    tenant_id    BIGINT       NULL,
    user_id      BIGINT       NULL,
    role         VARCHAR(20)  NULL,
    session_id   VARCHAR(64)  NULL,
    path         VARCHAR(200) NULL,
    props        JSONB        NOT NULL DEFAULT '{}',
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_product_event_name_time ON product_event (name, occurred_at DESC);
CREATE INDEX idx_product_event_session   ON product_event (session_id, occurred_at);
