-- Phase 2, Task 12 — the six structural guarantees.
--
-- These constraints carry the correctness properties the whole system rests on:
-- exactly one open SLA clock segment, exactly one escalation per rung, one live
-- incident per ticket. A partial index written without its WHERE clause creates
-- successfully, behaves plausibly, and breaks silently in week 12.
--
-- Each NEGATIVE asserts the second insert FAILS with the named constraint.
-- Each POSITIVE asserts the legitimate path still WORKS — a constraint that
-- blocks valid behaviour is as broken as one that permits invalid behaviour.
--
-- Everything runs inside a transaction that is rolled back at the end.

\set ON_ERROR_STOP on
BEGIN;

-- ── fixtures ────────────────────────────────────────────────────────────────
INSERT INTO tenant (id, name, slug) VALUES (9001, 'Guarantee Test', 'gtest');
INSERT INTO team (id, tenant_id, name, skills, is_default)
     VALUES (9001, 9001, 'T', '{PAYMENT}', TRUE);
INSERT INTO app_user (id, tenant_id, email, password_hash, full_name, role)
     VALUES (9001, 9001, 'g@t.test', repeat('x',60), 'G', 'AGENT');
INSERT INTO ticket (id, tenant_id, reference, subject, body, requester_id)
     VALUES (9001, 9001, 'TKT-9001', 's', 'b', 9001),
            (9002, 9001, 'TKT-9002', 's', 'b', 9001);
INSERT INTO sla_policy (id, tenant_id, priority, plan_tier, first_response_minutes,
                        resolution_minutes, version_label)
     VALUES (9001, 9001, 'P1', 'PRO', 60, 480, 'v1');
INSERT INTO sla_record (id, ticket_id, tenant_id, sla_policy_id, policy_version, kind,
                        target_minutes)
     VALUES (9001, 9001, 9001, 9001, 'v1', 'FIRST_RESPONSE', 60);
INSERT INTO incident (id, tenant_id, reference, title, first_ticket_at)
     VALUES (9001, 9001, 'INC-9001', 'i1', NOW()),
            (9002, 9001, 'INC-9002', 'i2', NOW());
INSERT INTO incident_update (id, incident_id, author_id, body)
     VALUES (9001, 9001, 9001, 'update');
INSERT INTO ticket_event (ticket_id, tenant_id, event_type) VALUES (9001, 9001, 'CREATED');

CREATE TEMP TABLE result (n int, kind text, label text, pass boolean, detail text);

-- ── helper: expect a specific constraint violation ──────────────────────────
CREATE OR REPLACE FUNCTION pg_temp.expect_violation(n int, label text, constraint_name text, stmt text)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE got text;
BEGIN
  BEGIN
    EXECUTE stmt;
    INSERT INTO result VALUES (n,'NEGATIVE',label,false,'statement SUCCEEDED — constraint missing or wrong');
  EXCEPTION WHEN unique_violation OR check_violation OR raise_exception THEN
    GET STACKED DIAGNOSTICS got = CONSTRAINT_NAME;
    IF got = constraint_name OR (got IS NULL OR got = '') THEN
      INSERT INTO result VALUES (n,'NEGATIVE',label,true,COALESCE(NULLIF(got,''),'raised by trigger'));
    ELSE
      INSERT INTO result VALUES (n,'NEGATIVE',label,false,'wrong constraint: '||got);
    END IF;
  END;
END $$;

CREATE OR REPLACE FUNCTION pg_temp.expect_success(n int, label text, stmt text)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  BEGIN
    EXECUTE stmt;
    INSERT INTO result VALUES (n,'POSITIVE',label,true,'ok');
  EXCEPTION WHEN OTHERS THEN
    INSERT INTO result VALUES (n,'POSITIVE',label,false,SQLERRM);
  END;
END $$;

-- ═══ NEGATIVES ══════════════════════════════════════════════════════════════

-- 1. Exactly one OPEN clock segment per SLA record
INSERT INTO sla_clock_segment (sla_record_id, state, started_at) VALUES (9001,'RUNNING',NOW());
SELECT pg_temp.expect_violation(1, 'one open SLA segment per record', 'uq_segment_open',
  $$INSERT INTO sla_clock_segment (sla_record_id, state, started_at) VALUES (9001,'RUNNING',NOW())$$);

-- 2. One escalation per rung (turns an at-least-once poller into exactly-once)
INSERT INTO sla_escalation (sla_record_id, rung, elapsed_minutes_at_fire) VALUES (9001,50,30);
SELECT pg_temp.expect_violation(2, 'one escalation per rung', 'uq_escalation_rung',
  $$INSERT INTO sla_escalation (sla_record_id, rung, elapsed_minutes_at_fire) VALUES (9001,50,31)$$);

-- 3. One LIVE incident per ticket
INSERT INTO incident_ticket (incident_id, ticket_id) VALUES (9001, 9001);
SELECT pg_temp.expect_violation(3, 'one live incident per ticket', 'uq_incident_ticket_live',
  $$INSERT INTO incident_ticket (incident_id, ticket_id) VALUES (9002, 9001)$$);

-- 4. One LIVE sla_policy per (tenant, priority, plan_tier)
SELECT pg_temp.expect_violation(4, 'one live SLA policy per key', 'uq_sla_policy_live',
  $$INSERT INTO sla_policy (tenant_id, priority, plan_tier, first_response_minutes,
                            resolution_minutes, version_label)
    VALUES (9001,'P1','PRO',30,240,'v2')$$);

-- 5. Fan-out idempotency
INSERT INTO incident_update_delivery (incident_update_id, ticket_id) VALUES (9001, 9001);
SELECT pg_temp.expect_violation(5, 'fan-out delivery idempotency', 'uq_delivery',
  $$INSERT INTO incident_update_delivery (incident_update_id, ticket_id) VALUES (9001, 9001)$$);

-- 6. ticket_event is append-only (trigger, not constraint)
SELECT pg_temp.expect_violation(6, 'ticket_event append-only', 'trigger',
  $$UPDATE ticket_event SET to_value='x' WHERE ticket_id=9001$$);

-- ═══ POSITIVES — the legitimate paths must still work ═══════════════════════

-- 7. Close a segment, then open another
SELECT pg_temp.expect_success(7, 'close segment then open a new one',
  $$UPDATE sla_clock_segment SET ended_at=NOW() WHERE sla_record_id=9001 AND ended_at IS NULL;
    INSERT INTO sla_clock_segment (sla_record_id, state, started_at, pause_reason)
    VALUES (9001,'PAUSED',NOW(),'WAITING_ON_CUSTOMER')$$);

-- 8. A DETACHED incident link permits a new live one
SELECT pg_temp.expect_success(8, 'detach then re-link to another incident',
  $$UPDATE incident_ticket SET detached_at=NOW() WHERE ticket_id=9001 AND detached_at IS NULL;
    INSERT INTO incident_ticket (incident_id, ticket_id) VALUES (9002, 9001)$$);

-- 9. A SUPERSEDED policy permits a new live one (effective dating)
SELECT pg_temp.expect_success(9, 'supersede policy then insert successor',
  $$UPDATE sla_policy SET effective_to=NOW() WHERE id=9001;
    INSERT INTO sla_policy (tenant_id, priority, plan_tier, first_response_minutes,
                            resolution_minutes, version_label)
    VALUES (9001,'P1','PRO',30,240,'v2')$$);

-- 10. A different rung on the same record is allowed
SELECT pg_temp.expect_success(10, 'a different escalation rung is allowed',
  $$INSERT INTO sla_escalation (sla_record_id, rung, elapsed_minutes_at_fire) VALUES (9001,75,45)$$);

-- ═══ REPORT ═════════════════════════════════════════════════════════════════
SELECT n,
       kind,
       CASE WHEN pass THEN 'PASS' ELSE 'FAIL' END AS status,
       label,
       detail
  FROM result ORDER BY n;

SELECT CASE WHEN count(*) FILTER (WHERE NOT pass) = 0
            THEN 'ALL ' || count(*)::text || ' GUARANTEES HOLD'
            ELSE count(*) FILTER (WHERE NOT pass)::text || ' FAILURES' END AS verdict
  FROM result;

ROLLBACK;
