-- V9__escalation_notification_link.sql
-- Narrows sla_escalation's append-only guard so the notification link can be set once.
--
-- WHY THIS EXISTS
--
-- The escalation row is inserted BEFORE the notification is created. That order is the
-- whole exactly-once mechanism: uq_escalation_rung rejects the second insert, so a rung
-- that has already fired sends nothing, and an at-least-once poller becomes an
-- exactly-once effect with no lock and no coordination.
--
-- Two facts then collide. sla_escalation.notification_id has a foreign key to
-- notification(id), so it cannot be populated at insert time — the notification does not
-- exist yet. And trg_escalation_immutable (V7) forbids every UPDATE on the table, so it
-- cannot be populated afterwards either. The result was an escalation row that could
-- never record which notification it sent.
--
-- The alternatives, and why not:
--   * Insert the notification first. Breaks the guard: a rung that turns out to have
--     already fired would have sent a duplicate alert before finding out.
--   * Reserve the notification id from its sequence and insert both with it. The foreign
--     key rejects a reference to a row that does not exist yet, and making it DEFERRABLE
--     to allow that weakens a constraint for the whole system to fix one write order.
--   * Drop the column. It is the only link from a fired rung to what was actually sent,
--     which is the first thing anyone asks when an alert is disputed.
--
-- So the guard is narrowed rather than removed, exactly as sla_clock_segment's already is
-- (V7 has the same shape: closing a segment is the one legal update). Everything that
-- makes the row an audit record — the rung, the record, the timestamp, the elapsed figure
-- — is still immutable. The single legal change is NULL -> a notification id, once.

CREATE OR REPLACE FUNCTION forbid_escalation_mutation() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Table sla_escalation is append-only: DELETE is not permitted';
    END IF;

    IF OLD.notification_id IS NOT NULL OR NEW.notification_id IS NULL THEN
        RAISE EXCEPTION 'sla_escalation % is append-only: only notification_id may be set, once',
            OLD.id;
    END IF;

    -- Every other column must be untouched. Written out rather than compared as a whole
    -- row so the error names what was changed.
    IF NEW.sla_record_id           IS DISTINCT FROM OLD.sla_record_id
       OR NEW.rung                 IS DISTINCT FROM OLD.rung
       OR NEW.fired_at             IS DISTINCT FROM OLD.fired_at
       OR NEW.elapsed_minutes_at_fire IS DISTINCT FROM OLD.elapsed_minutes_at_fire THEN
        RAISE EXCEPTION 'sla_escalation % is append-only: only notification_id may change',
            OLD.id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_escalation_immutable ON sla_escalation;
CREATE TRIGGER trg_escalation_immutable
    BEFORE UPDATE OR DELETE ON sla_escalation
    FOR EACH ROW EXECUTE FUNCTION forbid_escalation_mutation();
