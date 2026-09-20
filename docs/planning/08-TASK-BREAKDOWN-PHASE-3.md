# 08 — Task Breakdown: Phase 3

> Skill: `task-breakdown` · Run once per phase · **Re-run this skill at the start of Phase 4, 5, 6…**

**Phase:** Phase 3 — Project Setup & Boilerplate
**Goal:** A Spring Boot application that starts, connects to PostgreSQL and Redis, passes Flyway validation, returns RFC 7807 errors, exposes a health endpoint, and has a green Testcontainers integration test — with no features in it yet.
**Tech stack:** Java 21 · Spring Boot 3.3.x · Maven · PostgreSQL 16 + pgvector · Redis 7 · Flyway · Testcontainers · JUnit 5 · GitHub Actions

---

## TASK 1 — Generate the Spring Boot project skeleton

**📝 Description**
Generate the project at [start.spring.io](https://start.spring.io) with: Maven, Java 21, Spring Boot 3.3.x (latest patch), packaging JAR, group `com.resolveai`, artifact `resolveai`, package `com.resolveai`.

Select these starters only: **Spring Web, Spring Data JPA, Spring Security, Validation, Spring Boot Actuator, PostgreSQL Driver, Flyway Migration, Spring Data Redis (Lettuce), Lombok.**

Unzip into the repository created in Phase 1, commit, and confirm `mvn clean compile` succeeds. Do **not** run the app yet — it will fail without a datasource, which is expected.

**⛓️ Dependencies**
None — can start immediately (Phase 1 repo exists).

**✅ Expected Output**
`mvn clean compile` prints `BUILD SUCCESS`. `ResolveaiApplication.java` exists. `pom.xml` lists exactly the nine starters above.

**⏱️ Estimated Time**
30–45 minutes.

---

## TASK 2 — Add the remaining dependencies to `pom.xml`

**📝 Description**
Add the dependencies Initializr does not offer, with versions pinned in `<properties>`:

- `spring-ai-anthropic-spring-boot-starter` (or OpenAI) — pin the Spring AI BOM version
- `com.pgvector:pgvector` — vector type support
- `io.minio:minio` — S3 client
- `io.micrometer:micrometer-registry-prometheus`
- `io.github.resilience4j:resilience4j-spring-boot3`
- **Test scope:** `org.testcontainers:junit-jupiter`, `org.testcontainers:postgresql`, `com.redis:testcontainers-redis`, `org.wiremock:wiremock-standalone`, `net.jqwik:jqwik`, `org.springframework.security:spring-security-test`

Add the Spring AI BOM to `<dependencyManagement>`. Do not write any code that uses these yet.

**⛓️ Dependencies**
Task 1 (pom.xml exists).

**✅ Expected Output**
`mvn dependency:resolve` completes with no errors. `mvn clean compile` still succeeds. No version conflict warnings in the output.

**⏱️ Estimated Time**
45–60 minutes. *(Add 30 minutes if the Spring AI BOM version needs hunting — the starter artifact names changed between 1.0 and 2.0, so check the version you actually pinned.)*

---

## TASK 3 — Create the package structure

**📝 Description**
Create the nine module packages under `com.resolveai`, each with empty `domain`, `service`, `web` and `repository` sub-packages where relevant:

```
com.resolveai
├── iam          ├── ticketing   ├── sla
├── triage       ├── knowledge   ├── incidents
├── drafting     ├── eval        └── platform
```

Add a `package-info.java` to each module stating in one sentence what it owns and which other modules it may depend on. Add `com.resolveai.common` for cross-cutting types (`ApiProblem`, `ErrorCode`, base DTOs).

**Constraint to write down now:** modules communicate through interfaces and outbox events, never by referencing each other's entities. The `package-info.java` files are where you record that.

**⛓️ Dependencies**
Task 1.

**✅ Expected Output**
Ten packages exist, each with a `package-info.java`. `mvn compile` succeeds.

**⏱️ Estimated Time**
30 minutes.

---

## TASK 4 — Write `application.yml` with three profiles

**📝 Description**
Replace `application.properties` with `application.yml`. Create a base config plus `application-local.yml`, `application-test.yml` and `application-prod.yml`.

Base must set:
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate          # NEVER update. Flyway owns the schema.
    open-in-view: false           # default is true and it is wrong
    properties:
      hibernate.jdbc.time_zone: UTC
  flyway:
    enabled: true
    baseline-on-migrate: false
  datasource:
    hikari:
      maximum-pool-size: 10
      leak-detection-threshold: 30000
management:
  endpoints.web.exposure.include: health,info,prometheus
  endpoint.health.show-details: always
```

`local` reads from `.env` via environment variables. `prod` reads every value from the environment with no defaults.

**⛓️ Dependencies**
Task 1.

**✅ Expected Output**
`application.yml` plus three profile files exist. `ddl-auto: validate` and `open-in-view: false` are both present — verify by reading the file back.

**⏱️ Estimated Time**
45–60 minutes.

---

## TASK 5 — Copy the Flyway migrations and verify they apply

**📝 Description**
Copy the SQL from [04 §Step 6](04-DATABASE-SCHEMA.md) into `src/main/resources/db/migration/` as seven files: `V1__extensions_and_tenancy.sql` … `V7__triggers.sql`.

Drop and recreate the `resolveai` database in the running Postgres container so you are migrating from truly empty. Run `mvn flyway:migrate` (or start the app once with security disabled).

Then verify in psql:
```sql
\dt                                    -- expect 37 tables
\di                                    -- indexes present
SELECT * FROM flyway_schema_history;   -- 7 rows, all success
SELECT extname FROM pg_extension;      -- vector, pg_trgm present
```

**⛓️ Dependencies**
Task 4 (datasource configured), Phase 1 (Postgres container with pgvector running).

**✅ Expected Output**
`flyway_schema_history` has 7 successful rows. `\dt` lists 37 tables. `vector` and `pg_trgm` appear in `pg_extension`.

**⏱️ Estimated Time**
1–1.5 hours. *(Most of this is fixing SQL typos found on first application — expect two or three.)*

---

## TASK 6 — Hand-verify the three structural guarantees

**📝 Description**
In psql, insert test rows and confirm each partial unique index rejects the second insert. **This is the highest-value fifteen minutes in the phase** — these three constraints carry the system's most important correctness properties, and finding a mistake now instead of in Phase 5 saves a confused day.

```sql
-- 1. One open SLA segment per record
INSERT INTO sla_clock_segment (sla_record_id, state, started_at)
  VALUES (1, 'RUNNING', NOW());
INSERT INTO sla_clock_segment (sla_record_id, state, started_at)
  VALUES (1, 'RUNNING', NOW());   -- MUST fail: uq_segment_open

-- 2. One escalation per rung
INSERT INTO sla_escalation (sla_record_id, rung, elapsed_minutes_at_fire)
  VALUES (1, 50, 120);
INSERT INTO sla_escalation (sla_record_id, rung, elapsed_minutes_at_fire)
  VALUES (1, 50, 121);            -- MUST fail: uq_escalation_rung

-- 3. One live incident per ticket
INSERT INTO incident_ticket (incident_id, ticket_id) VALUES (1, 1);
INSERT INTO incident_ticket (incident_id, ticket_id) VALUES (2, 1);
                                  -- MUST fail: uq_incident_ticket_live
```

You will need placeholder parent rows first. Roll the whole thing back afterwards. Also confirm the append-only trigger works: `UPDATE ticket_event SET to_value='x' WHERE id=1;` must raise an exception.

**⛓️ Dependencies**
Task 5.

**✅ Expected Output**
All three second-inserts fail with the **named** constraint in the error message. The `ticket_event` update raises `Table ticket_event is append-only`. Nothing is left in the database.

**⏱️ Estimated Time**
45 minutes.

---

## TASK 7 — Verify the Redis connection

**📝 Description**
Create `platform/config/RedisConfig.java` defining a `RedisTemplate<String, String>` with `StringRedisSerializer` for both keys and values (the default `JdkSerializationRedisSerializer` produces unreadable binary keys you will regret in `redis-cli`).

Write a temporary `CommandLineRunner` that sets a key, reads it back and logs the result. Run the app, confirm the log line, then confirm from `redis-cli GET resolveai:startup-check`. Delete the `CommandLineRunner` afterwards.

**⛓️ Dependencies**
Task 4, Phase 1 (Redis container running).

**✅ Expected Output**
The application logs the round-tripped value on startup, and `redis-cli GET` returns the same string as readable text, not binary. `CommandLineRunner` removed before commit.

**⏱️ Estimated Time**
45 minutes.

---

## TASK 8 — Define the error model: `ApiProblem` and `ErrorCode`

**📝 Description**
In `com.resolveai.common.error`, create:

- `ErrorCode` — an enum of every code from [05](05-API-CONTRACT.md): `VALIDATION_ERROR`, `UNAUTHORIZED`, `FORBIDDEN`, `TICKET_NOT_FOUND`, `VERSION_CONFLICT`, `ALREADY_ASSIGNED`, `ILLEGAL_TRANSITION`, `IDEMPOTENCY_KEY_REQUIRED`, `IDEMPOTENCY_KEY_CONFLICT`, `RATE_LIMITED`, `AI_BUDGET_EXHAUSTED`, `AI_DISABLED_BY_POLICY`, `INTERNAL_ERROR`, and the rest. Each carries a default HTTP status and a `type` URI suffix.
- `ApiProblem` — a record matching the RFC 7807 body exactly: `type`, `title`, `status`, `errorCode`, `detail`, `instance`, `timestamp`, `traceId`, plus optional `errors` for field violations.
- `FieldViolation` — `field`, `code`, `message`, `rejectedValue`.
- `ApiException` — a `RuntimeException` carrying an `ErrorCode` and a detail message, for services to throw.

No handler yet — that is Task 9.

**⛓️ Dependencies**
Task 3.

**✅ Expected Output**
Four types compile. `ErrorCode` has at least 14 constants, each with a status and type suffix.

**⏱️ Estimated Time**
1–1.5 hours.

---

## TASK 9 — Write the global `@RestControllerAdvice`

**📝 Description**
Create `common/error/GlobalExceptionHandler.java` annotated `@RestControllerAdvice`, returning `ApiProblem` with `Content-Type: application/problem+json` for:

| Exception | Status | Code |
|---|---|---|
| `MethodArgumentNotValidException` | 400 | `VALIDATION_ERROR` + populated `errors[]` |
| `ConstraintViolationException` | 400 | `VALIDATION_ERROR` |
| `ApiException` | from the code | from the code |
| `AccessDeniedException` | 403 | `FORBIDDEN` |
| `AuthenticationException` | 401 | `UNAUTHORIZED` |
| `OptimisticLockingFailureException` | 409 | `VERSION_CONFLICT` |
| `DataIntegrityViolationException` | 409 | `CONFLICT` |
| `Exception` (catch-all) | 500 | `INTERNAL_ERROR` |

Two rules the catch-all must follow: **the `detail` for a 500 is a generic string** (never the exception message — it leaks internals), and the real stack trace is logged at ERROR with the same `traceId` that goes into the response. Truncate `rejectedValue` to 100 characters and never echo a field named like a password or token.

**Build this now, in Phase 3.** Every endpoint written after this returns correct errors from its first line; retrofitting across 50 endpoints in Phase 9 is miserable and you will do it badly.

**⛓️ Dependencies**
Task 8.

**✅ Expected Output**
The handler compiles. A temporary controller with `@Valid` on a DTO with a `@NotBlank` field returns a 400 `application/problem+json` body containing `errorCode: "VALIDATION_ERROR"` and a populated `errors[]`. Verified in Postman.

**⏱️ Estimated Time**
2–2.5 hours. *(Add 1 hour if `@RestControllerAdvice` is new to you.)*

---

## TASK 10 — Configure structured JSON logging with MDC

**📝 Description**
Add `logstash-logback-encoder` and create `logback-spring.xml` with two appenders: human-readable pattern output for the `local` profile, JSON for `prod` and `test`.

Create `platform/logging/MdcFilter.java`, an `OncePerRequestFilter` registered earliest in the chain, that puts `traceId` (generate a UUID if no `traceparent` header is present) and `requestId` into the MDC, and **clears the MDC in a `finally` block** — a leaked MDC on a pooled thread attaches the wrong trace id to the next request.

Add an `X-Request-Id` response header. Leave `tenantId` and `userId` as documented placeholders; they get populated in Phase 4.

**⛓️ Dependencies**
Task 4.

**✅ Expected Output**
Requests under the `local` profile log a line containing a `traceId`. Under `prod` the same line is valid JSON with a `traceId` field. Every response carries `X-Request-Id`. MDC is cleared — verify by making two requests and confirming different trace ids.

**⏱️ Estimated Time**
1.5–2 hours.

---

## TASK 11 — Configure Actuator and a custom health indicator

**📝 Description**
Confirm `/actuator/health`, `/actuator/info` and `/actuator/prometheus` respond, and that the database and Redis health indicators appear in the details.

Create `platform/health/OutboxLagHealthIndicator.java` implementing `HealthIndicator`. For now it returns `UP` with a `oldestPendingEventAgeSeconds: 0` detail and a TODO — the `outbox_event` table exists but nothing writes to it until Phase 6. The point is that the indicator is wired now so Phase 6 only has to fill in the query.

Configure `management.endpoint.health.group.readiness` to include `db` and `redis`.

**⛓️ Dependencies**
Tasks 5, 7.

**✅ Expected Output**
`GET /actuator/health` returns `UP` with `db`, `redis` and `outboxLag` in the details. `GET /actuator/prometheus` returns metrics in Prometheus text format.

**⏱️ Estimated Time**
1 hour.

---

## TASK 12 — Build the Testcontainers base test class

**📝 Description**
Create `src/test/java/com/resolveai/IntegrationTestBase.java`:

- `@SpringBootTest(webEnvironment = RANDOM_PORT)` with `@ActiveProfiles("test")`
- A **`static`** `PostgreSQLContainer` using image `pgvector/pgvector:pg16`, annotated `@Container` and `@ServiceConnection`
- A **`static`** Redis container, also `@ServiceConnection`
- `@Testcontainers` on the class

**The containers must be `static`.** A non-static container starts a fresh Postgres per test class, taking your suite from ~40 seconds to ~8 minutes, at which point you stop running it and the whole investment is wasted.

Add `application-test.yml` with `ddl-auto: validate` and `flyway.enabled: true` so every test run exercises the real migrations against a real database.

**⛓️ Dependencies**
Tasks 2, 4, 5.

**✅ Expected Output**
The class compiles. A trivial subclass with an empty `@Test` passes, and the log shows the container starting once and Flyway applying all 7 migrations.

**⏱️ Estimated Time**
1.5–2 hours. *(Add 1 hour if Testcontainers is new — the first container pull is slow and `@ServiceConnection` wiring is unfamiliar.)*

---

## TASK 13 — Write the first real integration test

**📝 Description**
Create `PlatformSmokeTest extends IntegrationTestBase` with three tests using `TestRestTemplate`:

1. `healthEndpointReturnsUp` — `GET /actuator/health` returns 200 with `"status":"UP"`
2. `flywayAppliedAllMigrations` — query `flyway_schema_history` via `JdbcTemplate`, assert 7 successful rows
3. `validationErrorReturnsProblemJson` — POST an invalid body to the temporary test controller from Task 9, assert 400, `Content-Type: application/problem+json`, and `errorCode: "VALIDATION_ERROR"` in the body

Test 3 is the important one: it proves the error contract from [05](05-API-CONTRACT.md) is real and not just documented.

**⛓️ Dependencies**
Tasks 9, 11, 12.

**✅ Expected Output**
`mvn verify` runs all three tests green. Total suite time under 90 seconds including container start.

**⏱️ Estimated Time**
1.5 hours.

---

## TASK 14 — Set up the GitHub Actions CI workflow

**📝 Description**
Create `.github/workflows/ci.yml` triggering on push and pull request to `main`:

1. `actions/checkout@v4`
2. `actions/setup-java@v4` with Temurin 21 and `cache: maven`
3. `mvn -B clean verify`
4. Upload surefire reports on failure

Testcontainers works on GitHub's Ubuntu runners without extra configuration — Docker is already available.

Add a build-status badge to the README. Push and confirm the run is green.

**⛓️ Dependencies**
Task 13 (there must be tests for CI to run).

**✅ Expected Output**
A green run on GitHub Actions in under 6 minutes. The README badge shows passing.

**⏱️ Estimated Time**
1–1.5 hours. *(Add 30 minutes if the first run fails on a Docker-in-CI issue — usually it does not, but budget for it.)*

---

## TASK 15 — Clean up and commit the phase

**📝 Description**
Remove the temporary test controller from Task 9 (keep it only if you moved it to `src/test`). Remove any leftover `CommandLineRunner`. Run `mvn clean verify` one final time from a fresh `docker compose down -v && docker compose up -d`.

Commit with a message listing what the phase delivered. Tag `phase-3-complete`.

Update the README with a "Running locally" section: prerequisites, `docker compose up -d`, `mvn spring-boot:run`, the health URL.

**⛓️ Dependencies**
Task 14.

**✅ Expected Output**
`docker compose down -v && docker compose up -d && mvn clean verify` succeeds from a completely empty database. No temporary scaffolding remains. Tag pushed.

**⏱️ Estimated Time**
45 minutes.

---

## 📅 Suggested Daily Schedule

**Day 1** *(3.5 hours)*
- Task 1 — Generate the project skeleton (0.75h)
- Task 2 — Add remaining dependencies (1h)
- Task 3 — Create the package structure (0.5h)
- Task 4 — `application.yml` with three profiles (1h)

**Day 2** *(3.25 hours)*
- Task 5 — Copy and verify Flyway migrations (1.5h)
- Task 6 — Hand-verify the three structural guarantees (0.75h)
- Task 7 — Verify the Redis connection (0.75h)

*These three go together: Day 2 is the day the infrastructure becomes real. Ending it with all 37 tables created and the partial indexes proven is a genuinely good stopping point.*

**Day 3** *(4 hours)*
- Task 8 — `ApiProblem` and `ErrorCode` (1.25h)
- Task 9 — Global `@RestControllerAdvice` (2.5h)

*The error model and its handler belong on the same day — splitting them means re-loading the whole RFC 7807 shape into your head twice.*

**Day 4** *(3 hours)*
- Task 10 — Structured JSON logging with MDC (1.75h)
- Task 11 — Actuator and the outbox-lag health indicator (1h)

**Day 5** *(3.5 hours)*
- Task 12 — Testcontainers base class (1.75h)
- Task 13 — First integration test (1.5h)

**Day 6** *(2.25 hours)*
- Task 14 — GitHub Actions CI (1.25h)
- Task 15 — Clean up, final verify, tag (0.75h)

**Day 7 — Buffer / Catch-up**
Reserved. Realistically consumed by: Flyway SQL typos found in Task 5, the Spring AI BOM version hunt in Task 2, or the first Testcontainers run in Task 12. If none of those bite, use the day to start [06 §11](06-UI-UX-DESIGN.md) Tier 1 — the Tailwind token config — which is pure setup and unblocks the frontend later.

---

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
PHASE SUMMARY
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Total Tasks    : 15
Total Estimate : 15.5–20 hours
Suggested Days : 6 working days + 1 buffer
Hardest Task   : Task 9 — Global @RestControllerAdvice
                 (most new concepts at once: advice ordering, RFC 7807,
                  MDC-to-response trace correlation, and the discipline
                  of never leaking an exception message on a 500)
Most Skipped   : Task 6 — Hand-verifying the partial unique indexes.
                 It feels like it is not "real work" because no code is
                 written. It is the highest-value 45 minutes in the phase:
                 those three constraints carry the system's most important
                 correctness guarantees, and finding a mistake here instead
                 of in Phase 5 saves a confused day of debugging.
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

---

## Phase 3 exit checklist

Tick all seven before starting Phase 4:

- [ ] `docker compose down -v && docker compose up -d && mvn clean verify` passes from empty
- [ ] `flyway_schema_history` shows 7 successful migrations; `\dt` lists 37 tables
- [ ] All three partial unique indexes verified by hand; the append-only trigger fires
- [ ] `GET /actuator/health` returns `UP` with `db`, `redis` and `outboxLag`
- [ ] A validation error returns `application/problem+json` with a machine-readable `errorCode`
- [ ] Every log line carries a `traceId`; two requests produce different ones
- [ ] CI is green on GitHub and the README badge shows passing

---

## Next Steps

> ✅ **Task list ready for Phase 3 — Project Setup & Boilerplate!**
>
> **How to use this:**
> Work through the tasks in order. Each one is scoped to be completable in a single sitting, and each has a verifiable output — if you cannot tick the Expected Output, the task is not done, regardless of how much code you wrote.
>
> **A note on the excluded skill:** `task-execution` was deliberately left out of this planning set. These tasks are specific enough to act on directly, and the design documents ([03](03-SYSTEM-ARCHITECTURE.md)–[06](06-UI-UX-DESIGN.md)) already contain the decisions that task-execution would otherwise have to re-derive.
>
> **When Phase 3 is complete:**
> Run the `task-breakdown` skill again on **Phase 4 — Core Authentication & User Management**, using the phase definition in [07](07-DEV-PHASES.md). Do not write a task list for Phase 5 yet — it would be stale by the time you reached it, and the shape of Phase 5 will be clearer once auth and the tenant filter are real.
