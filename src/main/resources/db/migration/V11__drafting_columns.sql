-- V11__drafting_columns.sql
-- Columns V4's draft/draft_claim/knowledge_chunk left for Phase 7 to fill in.
--
-- V4 was written in Phase 2, against doc 04's schema, before doc 15's task breakdown
-- existed. Doc 04 has draft's cost and provenance columns (tokens, cost, model, prompt
-- version) because doc 05's response body was already drafted by then; it does not have
-- assembled_text, an instruction, a suppression reason, or a table for unresolved
-- aspects, because those came out of thinking through the coverage-suppression design
-- in doc 15 Tasks 16-17, months later. Rather than editing V4 — which Flyway checksums,
-- and which two other phases have already been applied on top of — the gap is closed
-- here, the same way V8 and V9 closed gaps found after their phases shipped.
--
-- DO NOT EDIT once applied: Flyway checksums this file.

-- ── draft ───────────────────────────────────────────────────────────────────

ALTER TABLE draft ADD COLUMN instruction        VARCHAR(500) NULL;
ALTER TABLE draft ADD COLUMN assembled_text     TEXT         NULL;
ALTER TABLE draft ADD COLUMN suppression_reason TEXT         NULL;

COMMENT ON COLUMN draft.assembled_text IS
    'NULL for every non-SHOWN status, including SHOWN''s two suppressed siblings. Never '
    'a hedged partial draft: a confidently wrong answer is worse than no answer, so a '
    'weak draft has no assembled text to accidentally send, not a caveated one.';

-- ── draft_claim ─────────────────────────────────────────────────────────────

ALTER TABLE draft_claim ADD COLUMN rejection_reason VARCHAR(500) NULL;

COMMENT ON COLUMN draft_claim.rejection_reason IS
    'Why kept = false. Populated by the numeric pre-filter (naming the unsupported '
    'value) or the entailment verifier (naming what the cited span does not say). '
    'Returned to the agent on every claim, kept or not - hiding a rejection is how the '
    'verification mechanism becomes invisible and therefore untrusted.';

-- ── draft_unresolved_aspect ─────────────────────────────────────────────────
-- What the ticket asked that the retrieved context did not cover. Its own table rather
-- than a JSONB array column on draft, because it is queried on its own in Phase 7E's
-- knowledge-gap mining ("which unresolved aspects recur across tickets?") - a query a
-- JSONB array would need a GIN index and a containment operator to answer at all.

CREATE TABLE draft_unresolved_aspect (
    id         BIGSERIAL   PRIMARY KEY,
    draft_id   BIGINT      NOT NULL,
    ordinal    INTEGER     NOT NULL,
    text       VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_unresolved_ordinal UNIQUE (draft_id, ordinal),
    CONSTRAINT fk_unresolved_draft FOREIGN KEY (draft_id)
        REFERENCES draft(id) ON DELETE CASCADE
);

-- ── knowledge_chunk ─────────────────────────────────────────────────────────
-- Doc 15 Task 5: a model change must be detected as a reason to re-embed, or switching
-- embedding models silently leaves a mixed-dimension index producing nonsense similarity
-- scores with no error anywhere. Tracked per chunk rather than per document, because a
-- document reindexed under model A and one still on model B can coexist during a
-- migration and the skip logic needs to tell them apart chunk by chunk.

ALTER TABLE knowledge_chunk ADD COLUMN embedding_model_id VARCHAR(80) NOT NULL
    DEFAULT 'text-embedding-3-small';
ALTER TABLE knowledge_chunk ALTER COLUMN embedding_model_id DROP DEFAULT;

COMMENT ON COLUMN knowledge_chunk.embedding_model_id IS
    'The model this chunk''s embedding was computed with. A reindex skip additionally '
    'requires this to match the currently configured model, on top of the content-hash '
    'check on the parent document.';
