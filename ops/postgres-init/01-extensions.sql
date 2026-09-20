-- Runs ONCE, on first container creation, from /docker-entrypoint-initdb.d/.
--
-- Flyway's V1 migration also issues CREATE EXTENSION IF NOT EXISTS (doc 04
-- §Step 6), so this is not the only path. It exists so that a bare
-- `docker compose up` yields a database that is ready before any application
-- has run — which is what makes `docker compose down -v && up` a true test
-- rather than one that quietly depends on a manual psql session.

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
