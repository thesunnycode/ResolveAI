-- V8__resolved_ticket_stat.sql
-- Historical resolution times, for breach prediction.
--
-- ─────────────────────────────────────────────────────────────────────────────
-- WHY A TABLE AND NOT THE MATERIALIZED VIEW THE PLAN ASKS FOR
--
-- docs/planning/10-TASK-BREAKDOWN-PHASE-5.md Task 30 specifies a materialized view over
-- the last 90 days of resolved tickets, refreshed nightly, with each ticket's resolution
-- time in BUSINESS minutes.
--
-- A materialized view cannot produce that number. Business minutes are the output of
-- BusinessHours.elapsedBusinessMinutes: a walk over sla_clock_segment intersected with the
-- tenant's working window, its working-day set, and its holiday list, in the tenant's IANA
-- zone with daylight saving applied. Expressed in SQL that is a correlated
-- generate_series per segment per ticket, reimplementing the arithmetic a second time -
-- and a second implementation of the hardest logic in the project is a second
-- implementation that can disagree with the first.
--
-- It would disagree in the worst possible way, too: the prediction would be computed with
-- different arithmetic from the clock it is predicting a breach of. "Predicted 180 minutes,
-- remaining budget 34" would be comparing two numbers that do not mean the same thing.
--
-- So the figure is computed once, by the real arithmetic, in the transaction that resolves
-- the ticket, and appended here. What is lost is the ability to backfill by re-running a
-- view; what is gained is one definition of a business minute. Backfilling is a one-off
-- script if it is ever needed.
--
-- Two other things fall out of this that the matview would not have given:
--   * the numbers are current, not up to 24 hours stale
--   * there is no REFRESH to schedule, and no nightly job to fail quietly
-- ─────────────────────────────────────────────────────────────────────────────
--
-- DO NOT EDIT once applied: Flyway checksums this file.

CREATE TABLE resolved_ticket_stat (
    id               BIGSERIAL   PRIMARY KEY,
    tenant_id        BIGINT      NOT NULL,
    ticket_id        BIGINT      NOT NULL,
    category         VARCHAR(40) NULL,
    priority         VARCHAR(12) NOT NULL
                     CONSTRAINT ck_rts_priority CHECK (priority IN ('P1','P2','P3','P4')),
    team_id          BIGINT      NULL,
    business_minutes INTEGER     NOT NULL
                     CONSTRAINT ck_rts_minutes CHECK (business_minutes >= 0),
    resolved_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- A ticket resolved, reopened and resolved again contributes two rows. That is
    -- correct: they were two pieces of work, and averaging them into one would hide
    -- exactly the tickets worth predicting about.
    CONSTRAINT uq_rts_resolution UNIQUE (ticket_id, resolved_at),
    CONSTRAINT fk_rts_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id) ON DELETE CASCADE,
    CONSTRAINT fk_rts_ticket FOREIGN KEY (ticket_id)
        REFERENCES ticket(id) ON DELETE CASCADE,
    CONSTRAINT fk_rts_team   FOREIGN KEY (team_id)
        REFERENCES team(id)   ON DELETE SET NULL
);

-- Serves the percentile query and its two fallbacks, which drop the trailing columns:
--   (tenant, priority, category, team) -> (tenant, priority, category) -> (tenant, priority)
-- Column order is therefore not arbitrary: it is most-general first, so one index covers
-- all three groupings.
CREATE INDEX idx_rts_lookup
    ON resolved_ticket_stat(tenant_id, priority, category, team_id, resolved_at DESC);

COMMENT ON COLUMN resolved_ticket_stat.business_minutes IS
    'Resolution time in BUSINESS minutes, computed by BusinessHours at resolve time. '
    'Written once, never recomputed, so it cannot drift from the clock it came from.';
