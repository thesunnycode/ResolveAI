-- Mirrors ops/postgres-init/01-extensions.sql, which docker compose runs for local
-- development. Keeping the two paths identical means a test cannot pass because of an
-- extension the real database would not have had.
--
-- V1 also issues CREATE EXTENSION IF NOT EXISTS, so this is a belt-and-braces duplicate
-- rather than the only path - which is the arrangement Phase 2 settled on after a
-- `docker compose down -v` revealed pgvector was present only because it had been created
-- by hand.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
