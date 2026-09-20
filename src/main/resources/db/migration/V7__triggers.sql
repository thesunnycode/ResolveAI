-- V7__triggers.sql
-- Triggers
-- Generated from docs/planning/04-DATABASE-SCHEMA.md at Phase 2.
-- DO NOT EDIT once applied: Flyway checksums this file.

-- updated_at maintenance for tables also written by NATIVE queries,
-- which bypass Hibernate's @UpdateTimestamp entirely.
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ticket_updated        BEFORE UPDATE ON ticket
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_sla_record_updated    BEFORE UPDATE ON sla_record
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_agent_profile_updated BEFORE UPDATE ON agent_profile
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_incident_updated      BEFORE UPDATE ON incident
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Append-only enforcement. Convention is not a constraint.
CREATE OR REPLACE FUNCTION forbid_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Table % is append-only: % is not permitted',
        TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ticket_event_immutable
    BEFORE UPDATE OR DELETE ON ticket_event
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_escalation_immutable
    BEFORE UPDATE OR DELETE ON sla_escalation
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_prompt_immutable
    BEFORE UPDATE OF template, model_id, output_schema ON prompt_version
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
-- NOTE: sla_clock_segment is NOT fully immutable — closing a segment sets
-- ended_at exactly once. Enforced by a narrower guard instead:
CREATE OR REPLACE FUNCTION forbid_segment_reopen() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.ended_at IS NOT NULL THEN
        RAISE EXCEPTION 'sla_clock_segment % is closed and cannot be modified', OLD.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_segment_close_once
    BEFORE UPDATE ON sla_clock_segment
    FOR EACH ROW EXECUTE FUNCTION forbid_segment_reopen();

-- Full-text vectors maintained by the database, so they can never drift
-- out of sync with the columns they index.
CREATE OR REPLACE FUNCTION ticket_tsv_update() RETURNS TRIGGER AS $$
BEGIN
    NEW.search_tsv :=
        setweight(to_tsvector('english', COALESCE(NEW.subject,'')), 'A') ||
        setweight(to_tsvector('english', COALESCE(NEW.body,'')),    'B');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ticket_tsv BEFORE INSERT OR UPDATE OF subject, body ON ticket
    FOR EACH ROW EXECUTE FUNCTION ticket_tsv_update();

CREATE OR REPLACE FUNCTION chunk_tsv_update() RETURNS TRIGGER AS $$
BEGIN
    NEW.text_tsv := to_tsvector('english', COALESCE(NEW.text,''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_chunk_tsv BEFORE INSERT OR UPDATE OF text ON knowledge_chunk
    FOR EACH ROW EXECUTE FUNCTION chunk_tsv_update();

DELETE FROM outbox_event
 WHERE status = 'DONE' AND created_at < NOW() - INTERVAL '7 days';
