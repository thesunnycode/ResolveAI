# 09 — Task Breakdown: Phase 4

> Skill: `task-breakdown` · Run once per phase · **Re-run at the start of Phase 5**

**Phase:** Phase 4 — Core Authentication & User Management
**Goal:** All four roles working end to end with JWT access tokens and rotating refresh tokens, tenant isolation enforced *below* the repository layer, and a test suite that proves one tenant cannot read another tenant's data.
**Tech stack:** Java 21 · Spring Boot 3.3.x · Spring Security 6 · Spring Data JPA / Hibernate 6 · PostgreSQL 16 · jjwt 0.12.x · BCrypt · Testcontainers · JUnit 5

---

## ⚠️ Estimate correction before you start

[07 — Development Phases](07-DEV-PHASES.md) put Phase 4 at **4–5 days**. Breaking it into real tasks, it comes to **26–30 hours ≈ 7–8 working days** at 3–4 focused hours a day.

The original figure was optimistic in one specific place: it assumed "30% is porting from Hyperlocal", which is true for `JwtService`, the auth filter and `SecurityConfig` — but those are only about a third of the phase. The other two thirds are **genuinely new work**: Hibernate-level tenant isolation, refresh-token rotation with reuse detection, and the cross-tenant test suite. None of that exists in Hyperlocal to port.

**Revised project total: 49–63 working days ≈ 10–13 calendar weeks.** Correct [07](07-DEV-PHASES.md)'s summary table when you next open it. Better to know now than in Week 6.

---

## TASK 1 — Create the five IAM entity classes

**📝 Description**
Create the entities in `com.resolveai.iam.domain`, mapping exactly to the schema in [04 §Group 1](04-DATABASE-SCHEMA.md): `Tenant`, `Team`, `AppUser`, `AgentProfile`, `RefreshToken`.

Follow the mapping notes in [04 §Step 8](04-DATABASE-SCHEMA.md) precisely:
- `@Id @GeneratedValue(strategy = IDENTITY)` on every entity
- **`@Enumerated(EnumType.STRING)`** on `AppUser.role` — never `ORDINAL`
- `@CreationTimestamp` / `@UpdateTimestamp` on `createdAt` / `updatedAt`
- **`@Version`** on `AgentProfile`
- **All `@ManyToOne` are `FetchType.LAZY`** — an `EAGER` `@ManyToOne` is the N+1 factory
- Soft delete on `AppUser` and `Team`: `@SQLDelete(sql = "UPDATE app_user SET deleted_at = NOW() WHERE id = ?")` plus `@SQLRestriction("deleted_at IS NULL")` (Hibernate 6's replacement for the deprecated `@Where`)
- `Team.skills` is a real `String[]` with `@JdbcTypeCode(SqlTypes.ARRAY)`, **not** a comma-separated string
- `RefreshToken.familyId` is a `UUID`

Create the `Role` enum (`CUSTOMER`, `AGENT`, `TEAM_LEAD`, `ADMIN`) and `PlanTier` enum (`FREE`, `PRO`, `ENTERPRISE`) in the same package.

Do **not** add the `@TenantId` annotation yet — that is Task 5, and adding it before the resolver exists will break context startup.

**⛓️ Dependencies**
None — Phase 3 delivered the schema and a validating context.

**✅ Expected Output**
Five entity classes and two enums compile. `mvn spring-boot:run` starts and `ddl-auto: validate` passes — meaning every field, type and nullability matches the Flyway-created schema. **If validation fails, the entity is wrong, not the schema.**

**⏱️ Estimated Time**
2–2.5 hours.

---

## TASK 2 — Create the five Spring Data repositories

**📝 Description**
Create repository interfaces in `com.resolveai.iam.repository`, each extending `JpaRepository<T, Long>`:

- `TenantRepository` — `Optional<Tenant> findBySlugAndIsActiveTrue(String slug)`
- `TeamRepository` — `List<Team> findByTenantId(Long)`, `Optional<Team> findByTenantIdAndIsDefaultTrue(Long)`
- `AppUserRepository` — `Optional<AppUser> findByTenantIdAndEmailIgnoreCase(Long, String)`, `boolean existsByTenantIdAndEmailIgnoreCase(Long, String)`
- `AgentProfileRepository` — `Optional<AgentProfile> findByUserId(Long)`
- `RefreshTokenRepository` — `Optional<RefreshToken> findByTokenHash(String)`, plus a modifying query `revokeFamily(UUID familyId, OffsetDateTime at)` that sets `revoked_at` on every unrevoked token in the family

Annotate the family-revocation method `@Modifying @Transactional`. Add `@Query` only where a derived name would be unreadable.

**⛓️ Dependencies**
Task 1 (entities must exist).

**✅ Expected Output**
Five interfaces compile and the application context starts — Spring Data validates derived query method names at startup, so a typo in `findByTenantIdAndEmailIgnoreCase` fails the boot rather than failing at runtime.

**⏱️ Estimated Time**
1–1.5 hours.

---

## TASK 3 — Write the entity–schema mapping smoke test

**📝 Description**
Create `IamMappingTest extends IntegrationTestBase` (from Phase 3, Task 12). For each of the five entities: persist one instance with every field populated, flush, clear the persistence context, re-read by id, and assert every field round-trips.

Specifically verify the things `ddl-auto: validate` does **not** catch:
- `Role` persists as the string `"AGENT"`, not an integer — read it back with a raw `JdbcTemplate` query and assert on the `String`
- `Team.skills` round-trips as a `String[]` of the right length
- `AppUser.deletedAt` soft delete works: call `delete()`, then assert `findById()` returns empty **and** a raw SQL count still returns 1
- `AgentProfile.version` increments on update

**⛓️ Dependencies**
Task 2 (repositories needed to persist).

**✅ Expected Output**
`mvn verify` green. The enum-as-string assertion in particular passes — this is the one mapping mistake that silently corrupts every row if you ever reorder the enum, and it is cheap to pin down now.

**⏱️ Estimated Time**
1–1.5 hours.

---

## TASK 4 — Build the `TenantContext` holder

**📝 Description**
Create `com.resolveai.platform.tenant.TenantContext` — a `ThreadLocal<Long>` holder with `set(Long)`, `get()`, `getRequired()` (throws if unset) and `clear()`.

Create `TenantContextFilter`, a `OncePerRequestFilter` ordered **after** the JWT filter (which does not exist yet — order it by constant now and wire in Task 8). For now it is a no-op stub with the structure in place.

**Two non-negotiable details:**
1. **`clear()` must run in a `finally` block.** A leaked `ThreadLocal` on a pooled Tomcat thread means the *next* request runs under the previous request's tenant — which is a cross-tenant data breach caused by a missing `finally`.
2. Background workers have no request thread. Add `TenantContext.runAs(Long tenantId, Runnable)` for Phase 6's workers to use, which sets, runs, and clears.

Also update the `MdcFilter` from Phase 3 Task 10 to populate the `tenantId` and `userId` MDC placeholders it currently leaves as TODOs.

**⛓️ Dependencies**
None — pure infrastructure, can be written in parallel with Tasks 1–3.

**✅ Expected Output**
`TenantContext` compiles. A unit test asserts that `runAs` clears the context even when the `Runnable` throws. `MdcFilter` no longer has TODO placeholders.

**⏱️ Estimated Time**
1–1.5 hours.

---

## TASK 5 — Add `@TenantId` and implement `CurrentTenantIdentifierResolver`

**📝 Description**
This is the **mechanism that makes a forgotten `WHERE tenant_id` impossible**, and it is the most valuable thing you build in this phase.

Use **Hibernate 6's discriminator multi-tenancy**, not a hand-rolled `@Filter`:

1. Annotate the `tenantId` field on every tenant-scoped entity with `@TenantId`. For Phase 4 that is `AppUser`, `Team`, `AgentProfile`. Every entity added in Phases 5–8 gets the same annotation.
2. Implement `ResolveTenantIdentifierResolver implements CurrentTenantIdentifierResolver<Long>`, returning `TenantContext.get()`, with `validateExistingCurrentSessions()` returning `false`.
3. Register it: `spring.jpa.properties.hibernate.tenant_identifier_resolver` pointing at the bean, or a `HibernatePropertiesCustomizer`.

**Why `@TenantId` rather than `@Filter`:** Hibernate applies the discriminator to *every* query automatically **and** sets it on insert, so you cannot persist a row into the wrong tenant either. `@Filter` has to be enabled manually on each session, which means one forgotten call reopens the hole you were closing.

**Write down the known gap now, because you will be asked about it:** `@TenantId` does **not** apply to native SQL queries. Phases 6–8 use native queries for the outbox claim and hybrid search. Those must carry an explicit `WHERE tenant_id = :tenantId`, and Task 19's test suite is what catches it if you forget.

**⛓️ Dependencies**
Tasks 1 and 4 (entities and `TenantContext`).

**✅ Expected Output**
Application starts with the resolver registered. A test that sets `TenantContext` to tenant A and calls `appUserRepository.findAll()` returns only tenant A's users, **with no `WHERE tenant_id` written anywhere in the repository**. Check the SQL log to confirm Hibernate added the predicate itself.

**⏱️ Estimated Time**
2.5–3 hours. *(Add 1 hour if Hibernate multi-tenancy is new — the resolver registration is the fiddly part and the error messages are unhelpful.)*

---

## TASK 6 — Write the tenant isolation unit test

**📝 Description**
Create `TenantIsolationTest extends IntegrationTestBase`. Seed two tenants, each with two users and one team, directly via `JdbcTemplate` so the seeding itself bypasses the filter.

Then assert, with `TenantContext` set to tenant A:
- `findAll()` on users, teams and agent profiles returns only tenant A's rows
- `findById()` on a **tenant B** id returns `Optional.empty()` — not the row
- Persisting a new `AppUser` writes tenant A's id even if you try to set tenant B's explicitly
- With `TenantContext` cleared, a repository call either returns nothing or throws — **assert whichever behaviour you actually get and document it**, because "no tenant set" must never mean "all tenants"

That last assertion is the important one. The default failure mode of a badly-built tenant filter is that an unset context silently disables filtering.

**⛓️ Dependencies**
Task 5 (the resolver must be live).

**✅ Expected Output**
All four assertions green. In particular, the `findById` on a foreign id returns empty — proving isolation works on the direct-lookup path, not just on list queries.

**⏱️ Estimated Time**
1.5 hours.

---

## TASK 7 — Write the `JwtService`

**📝 Description**
Create `com.resolveai.iam.security.JwtService` using jjwt 0.12.x.

`generateAccessToken(AppUser)` produces a token with claims: `sub` (user id), `tenantId`, `tenantSlug`, `role`, `jti` (UUID), `iss`, `aud`, `iat`, `exp` (now + `JWT_ACCESS_TTL_MINUTES`, default 15).

`parseAndValidate(String) → Jws<Claims>` verifies signature, expiry, **issuer and audience**, and throws a typed exception on each distinct failure so the filter can distinguish expired from malformed.

**Three details that matter:**
1. **Pin the algorithm to HS256 on verification.** Accepting whatever `alg` the token declares is the `alg: none` vulnerability. jjwt 0.12 does this correctly by default when you build the parser with a `SecretKey`, but assert it in a test rather than assuming.
2. The signing key comes from `JWT_SECRET` and must be **at least 256 bits**. Fail fast at startup with a clear message if it is shorter, rather than at first login.
3. This is mostly a port from Hyperlocal — but the `tenantId` and `tenantSlug` claims are new, and issuer/audience validation probably was not there.

**⛓️ Dependencies**
Task 1 (needs `AppUser` and `Role`).

**✅ Expected Output**
Unit tests pass: a generated token round-trips all claims; an expired token throws; a token signed with a different key throws; a token with a tampered payload throws; a token with the wrong `aud` throws.

**⏱️ Estimated Time**
2 hours *(largely porting)*.

---

## TASK 8 — Write the `JwtAuthenticationFilter`

**📝 Description**
Create `JwtAuthenticationFilter extends OncePerRequestFilter`:

1. Read the `Authorization` header; if absent or not `Bearer `, continue the chain unauthenticated
2. Parse and validate via `JwtService`
3. Build a `ResolvePrincipal` (a record holding `userId`, `tenantId`, `tenantSlug`, `role`) and set it as the authentication principal with `ROLE_` authorities
4. **Set `TenantContext` from the token's `tenantId` claim**
5. **Clear `TenantContext` in a `finally` block**

Register it before `UsernamePasswordAuthenticationFilter`.

**The tenant must come from the token and only from the token.** Never from a header, a path variable or a request body — that would let any authenticated user read any tenant by changing a parameter.

On a validation failure, do **not** throw from the filter. Set no authentication and let Spring Security's entry point produce the `401`, so the response still goes through the Phase 3 `@RestControllerAdvice` and comes out as RFC 7807.

**⛓️ Dependencies**
Tasks 4 and 7 (`TenantContext` and `JwtService`).

**✅ Expected Output**
The filter compiles and is registered. A test hitting a protected endpoint with a valid token reaches the controller with a populated principal; with a malformed token it returns `401` with `Content-Type: application/problem+json`.

**⏱️ Estimated Time**
2 hours *(partly porting; the `TenantContext` wiring is new)*.

---

## TASK 9 — Write the `TenantAwareUserDetailsService`

**📝 Description**
Spring Security's `UserDetailsService` interface takes a single `String username`, but this system needs `(tenantSlug, email)`. Two workable approaches — pick one and note why:

- **Composite username**: encode as `"acme:priya@example.com"` and split in `loadUserByUsername`. Ugly but standard-interface-compatible.
- **Custom interface**: `loadUser(String tenantSlug, String email)`, used directly by the login service and not wired into an `AuthenticationProvider` at all.

**Recommended: the custom interface.** Login is the only place that needs it, the JWT filter builds its principal from token claims rather than a database lookup, and the composite-string hack exists only to satisfy an interface you are not actually using.

Return a `ResolveUserDetails` implementing `UserDetails` and additionally exposing `tenantId`, `tenantSlug` and `role`. Enforce `isEnabled()` from `is_active` and `isAccountNonLocked()` from `deleted_at IS NULL`.

**⛓️ Dependencies**
Task 2 (`AppUserRepository`).

**✅ Expected Output**
Loading a known `(slug, email)` returns the user with the correct authority. A deactivated user loads with `isEnabled() == false`. A soft-deleted user is not found at all.

**⏱️ Estimated Time**
1–1.5 hours.

---

## TASK 10 — Write `SecurityConfig`

**📝 Description**
Create `com.resolveai.iam.security.SecurityConfig` with `@EnableWebSecurity` and `@EnableMethodSecurity(prePostEnabled = true)`.

The `SecurityFilterChain` must set:
- `csrf().disable()` — stateless JWT API, no cookies
- `sessionManagement(STATELESS)`
- `authorizeHttpRequests`: `permitAll` for `/api/v1/auth/register`, `/api/v1/auth/login`, `/api/v1/auth/refresh`, `/actuator/health/**`; **`.anyRequest().authenticated()`**
- `addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)`
- Custom `AuthenticationEntryPoint` → `401` RFC 7807, and `AccessDeniedHandler` → `403` RFC 7807
- CORS from `CORS_ALLOWED_ORIGINS`, as an **explicit list**

Define the `PasswordEncoder` bean as `new BCryptPasswordEncoder(12)`.

**Two traps:**
1. **Never `allowedOrigins("*")` with `allowCredentials(true)`.** The browser rejects it, and reaching for a wildcard to "fix" a CORS error is how a real misconfiguration ships.
2. Order the `permitAll` matchers **before** `anyRequest()`. Spring Security matches in declaration order, and an `anyRequest().authenticated()` placed first silently locks out login.

**⛓️ Dependencies**
Tasks 8 and 9.

**✅ Expected Output**
Unauthenticated `GET /actuator/health` → `200`. Unauthenticated `GET /api/v1/tickets` → `401` in problem+json. `POST /api/v1/auth/login` is reachable without a token.

**⏱️ Estimated Time**
1.5–2 hours *(partly porting; the RFC 7807 entry point and handler are new)*.

---

## TASK 11 — Implement `POST /api/v1/auth/register`

**📝 Description**
Create `AuthController` and `RegistrationService`.

Implement the contract from [05 §3.1](05-API-CONTRACT.md) exactly: `RegisterRequest` DTO with Bean Validation (`@NotBlank`, `@Email`, `@Size(min=10, max=128)` on password, a `@Pattern` requiring at least one letter and one digit), returning `201` with a `UserResponse`.

Business rules:
- Resolve the tenant by slug; `404 TENANT_NOT_FOUND` if unknown or inactive
- Normalise email to lowercase before the uniqueness check and before persisting
- `409 EMAIL_ALREADY_EXISTS` on a duplicate within the tenant
- Always create with `role = CUSTOMER` — **the role must not be settable from the request body.** This is the mass-assignment trap; the DTO simply has no role field.
- Hash with the injected `PasswordEncoder`

Add the common-password check against a committed top-1000 list.

**⛓️ Dependencies**
Tasks 2 and 10.

**✅ Expected Output**
Postman: valid registration → `201` with the user body and no password field anywhere in the response. Duplicate → `409`. Weak password → `400` with a populated `errors[]`. Unknown tenant → `404`.

**⏱️ Estimated Time**
1.5–2 hours.

---

## TASK 12 — Implement `POST /api/v1/auth/login`

**📝 Description**
Implement `login` in `AuthService`, returning the token pair and user summary from [05 §3.1](05-API-CONTRACT.md).

Flow: resolve tenant → load user → verify password → issue access token → issue refresh token (Task 13 supplies the service; stub the call for now and wire it in that task) → update `last_login_at`.

**The security requirement that makes this task more than plumbing — constant-time failure:**

```java
var user = userDetailsService.loadUser(slug, email).orElse(null);
// ALWAYS run a BCrypt comparison, even when the user does not exist
var hash = user != null ? user.getPasswordHash() : DUMMY_BCRYPT_HASH;
boolean ok = passwordEncoder.matches(rawPassword, hash);
if (user == null || !ok) throw new ApiException(INVALID_CREDENTIALS);
```

A single `401 INVALID_CREDENTIALS` covers "no such user", "wrong password" and "wrong tenant". Returning different codes — or returning faster when the user does not exist — turns login into a user-enumeration oracle. **The dummy-hash comparison is what closes the timing side channel**, and it is the detail almost nobody implements.

`403 ACCOUNT_DISABLED` is the one distinct case, and only for an authenticated-but-deactivated user.

**⛓️ Dependencies**
Tasks 7, 9, 11.

**✅ Expected Output**
Valid login returns `200` with an access token that decodes to the right claims at [jwt.io](https://jwt.io). Wrong password, unknown email and unknown tenant **all** return an identical `401` body. A test asserts the response-time difference between "user exists, wrong password" and "user does not exist" is under 50ms.

**⏱️ Estimated Time**
2 hours.

---

## TASK 13 — Implement `RefreshTokenService` with rotation and reuse detection

**📝 Description**
The most security-sensitive component in the phase, and entirely new work — there is nothing to port.

`issue(AppUser user, UUID familyId)`:
- Generate 32 random bytes from `SecureRandom`, base64url-encode → the raw token returned to the client
- Store **only** `SHA-256(raw)` in `token_hash`. The raw token is never persisted.
- New `familyId` on login; the **same** `familyId` on rotation
- `expires_at = now + JWT_REFRESH_TTL_DAYS` (default 7)

`rotate(String rawToken) → TokenPair`:
1. Look up by `SHA-256(raw)`. Not found → `401 INVALID_REFRESH_TOKEN`
2. `revoked_at != null` or expired → `401 INVALID_REFRESH_TOKEN`
3. **`used_at != null` → reuse detected.** Revoke the entire `familyId`, then throw `401 TOKEN_REUSE_DETECTED`
4. Otherwise: set `used_at = now`, issue a new refresh token in the same family, issue a new access token

Step 3 is the whole point. A refresh token is single-use; seeing one presented twice means either it was stolen and replayed, or the legitimate client replayed it. **The system cannot tell which party is the attacker**, so it revokes the family and forces a fresh login. Rotation without detection is half a defence.

Run the whole of `rotate` in one transaction so a concurrent double-refresh cannot both pass step 3.

**⛓️ Dependencies**
Tasks 2 and 7.

**✅ Expected Output**
Unit tests: a fresh token rotates successfully; the old token then fails; **presenting the old token a second time revokes every token in the family**; an expired token fails; a token from a revoked family fails.

**⏱️ Estimated Time**
2.5–3 hours.

---

## TASK 14 — Implement `POST /auth/refresh` and `POST /auth/logout`

**📝 Description**
Wire `RefreshTokenService` into two endpoints.

`POST /auth/refresh` — public (the refresh token *is* the credential). Body `{ "refreshToken": "..." }`. Returns the same shape as login. Distinguish `INVALID_REFRESH_TOKEN` from `TOKEN_REUSE_DETECTED` in the `errorCode`, while returning `401` for both.

`POST /auth/logout` — authenticated. Revokes the presented refresh token's entire family. Returns `204`.

Then go back to Task 12's login and replace the stub with the real `issue()` call.

**A note to put in the README:** logout does not invalidate the *access* token — it is stateless and remains valid until it expires. The 15-minute TTL is what bounds the exposure. A deny-list in Redis keyed by `jti` is the fix, and it is deliberately deferred: it adds a Redis read to every single request, and the exposure window is already short. **Being able to state that trade-off is worth more than implementing it**, and "JWTs cannot be revoked instantly" is a question you will be asked.

**⛓️ Dependencies**
Tasks 12 and 13.

**✅ Expected Output**
Postman: login → refresh returns a new pair → the old refresh token now fails → presenting it again returns `TOKEN_REUSE_DETECTED` and the new token also stops working. Logout → `204`, and the refresh token no longer works.

**⏱️ Estimated Time**
1.5 hours.

---

## TASK 15 — Implement `GET /api/v1/auth/me`

**📝 Description**
Return the body from [05 §3.1](05-API-CONTRACT.md): identity, tenant, role, team, the `permissions` array, and `agentProfile` when the user is an agent.

Create `PermissionResolver` mapping each `Role` to its permission strings (`ticket:read:team`, `ticket:assign:self`, `incident:confirm`, …). Keep it a single switch over the enum — it is the one place the role-to-capability mapping is written down, and [06](06-UI-UX-DESIGN.md) consumes it to decide which nav items and buttons to render.

**State the boundary in a comment on the class:** this array exists so the frontend can hide controls the user cannot use. **The server remains the only authority** — every endpoint is independently protected by `@PreAuthorize`. A client that forges a permission string gains nothing.

**⛓️ Dependencies**
Tasks 10 and 12.

**✅ Expected Output**
A logged-in agent's `GET /auth/me` returns the correct role, team and permissions, with a populated `agentProfile`. A customer's response has `teamId: null`, no `agentProfile`, and a strictly smaller permissions array.

**⏱️ Estimated Time**
1–1.5 hours.

---

## TASK 16 — Add `@PreAuthorize` method security to the IAM service layer

**📝 Description**
Annotate service methods — **not controllers** — with `@PreAuthorize`.

Establish the pattern the rest of the project will follow:
```java
@PreAuthorize("hasRole('ADMIN')")
public UserResponse createAgent(CreateAgentRequest req) { ... }

@PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN')")
public void assignUserToTeam(Long userId, Long teamId) { ... }
```

Create `@IsAdmin`, `@IsTeamLeadOrAbove` and `@IsAgentOrAbove` as meta-annotations wrapping the expressions, so Phases 5–8 use a readable name rather than re-typing SpEL strings that can drift.

**Why the service layer and not the controller:** a service method called from a background worker, a scheduled job, or another service bypasses the controller entirely and would then have no check at all. Phases 6–8 add exactly those callers. Annotating the service is what makes the guarantee hold everywhere.

**⛓️ Dependencies**
Task 10 (`@EnableMethodSecurity`).

**✅ Expected Output**
An `AGENT` token calling an admin-only service method gets `403` in problem+json. The same method called with an `ADMIN` token succeeds. The three meta-annotations exist and are used.

**⏱️ Estimated Time**
1.5 hours.

---

## TASK 17 — Write the IAM seed loader

**📝 Description**
Create `platform/seed/IamSeeder`, a `CommandLineRunner` active only under the `local` profile and guarded by `app.seed.enabled`.

Create from the Phase 1 corpus: **3 tenants** (one per plan tier), **4 teams per tenant** with realistic `skills` arrays, and **users covering all four roles per tenant** — 1 admin, 2 team leads, 6 agents with `AgentProfile` rows, 20 customers. Also seed one `BusinessCalendar` per tenant, which Phase 5 needs immediately.

Make it **idempotent**: check whether tenant slug `acme` exists and return early if so, so restarting the app does not duplicate everything.

Print a summary table of seeded logins to the console at startup. You will type these credentials hundreds of times over the next eight weeks.

**The seed password is shared and known.** That is fine for local development and it is what makes the demo work — but note it in the README, because the pattern is catastrophic if copied.

**⛓️ Dependencies**
Tasks 2 and 11 (needs the encoder and repositories).

**✅ Expected Output**
`mvn spring-boot:run -Dspring-boot.run.profiles=local` on an empty database seeds 3 tenants, 12 teams and ~87 users, and prints the login table. Restarting does not duplicate. Every seeded user can log in through Postman.

**⏱️ Estimated Time**
2 hours.

---

## TASK 18 — Write the refresh-token rotation and reuse-detection integration test

**📝 Description**
Create `RefreshTokenFlowTest extends IntegrationTestBase`, driving real HTTP with `TestRestTemplate`:

1. `rotationIssuesNewPairAndInvalidatesOld` — login, refresh, assert new tokens differ, assert the old refresh token now `401`s
2. **`reusedTokenRevokesEntireFamily`** — login, refresh (→ token B), replay token A, assert `TOKEN_REUSE_DETECTED`, **then assert token B also now fails**. The second assertion is the actual test; the first is just setup.
3. `expiredTokenRejected` — insert a token with a past `expires_at` via `JdbcTemplate`, assert `401`
4. `logoutRevokesFamily`
5. `concurrentRefreshDoesNotIssueTwoValidTokens` — two threads released by a `CountDownLatch` refresh the same token; assert exactly one succeeds and the family is revoked

Test 5 is the interesting one and it is why `rotate()` runs in a single transaction.

**⛓️ Dependencies**
Tasks 13 and 14.

**✅ Expected Output**
All five green. Use `CountDownLatch` for test 5 — never `Thread.sleep`, which produces a flaky test that gets `@Disabled` within a week and then protects nothing.

**⏱️ Estimated Time**
1.5–2 hours.

---

## TASK 19 — Write the parameterised cross-tenant isolation test

**📝 Description**
**The most important test in the phase, and the one you will describe in interviews.**

Create `CrossTenantAccessTest extends IntegrationTestBase`. Seed two full tenants. Obtain a token for a user in tenant A.

Write a `@ParameterizedTest` fed by a `@MethodSource` listing **every endpoint that currently exists**, as `(method, pathTemplate, requiredRole)` triples. For each: substitute a **tenant B** resource id into the path, call it with tenant A's token, and assert the response is `404` or `403` — **never `200`, and never `500`**.

Add a second parameterised test asserting that every *list* endpoint called with tenant A's token returns zero tenant-B rows.

**Design the `@MethodSource` to be appended to.** Phases 5–8 add roughly 45 more endpoints, and every one gets a line here. That growing list is the evidence behind the claim "no tenant can read another tenant's data" — the `@TenantId` resolver is the mechanism, this test is the proof, and neither is sufficient alone.

**⛓️ Dependencies**
Tasks 5, 15, 17.

**✅ Expected Output**
Every current endpoint passes. Then **deliberately break it**: temporarily remove `@TenantId` from `AppUser`, watch the test go red, and put it back. A test you have never seen fail is a test you do not know works.

**⏱️ Estimated Time**
2–2.5 hours.

---

## TASK 20 — Clean up, verify and tag Phase 4

**📝 Description**
Remove the temporary test controller left from Phase 3 Task 9 if it is still in `src/main`. Run a full clean verification from an empty database:

```bash
docker compose down -v && docker compose up -d && mvn clean verify
```

Update the README: add an "Authentication" section covering the token model, TTLs, the rotation-and-reuse-detection design, and **the honest note that access tokens cannot be revoked before expiry** with the reasoning from Task 14.

Update the Postman collection: add the Auth folder, and add a collection-level script that stores the access token into an environment variable after login so every other request inherits it automatically.

Commit and tag `phase-4-complete`.

**⛓️ Dependencies**
Task 19.

**✅ Expected Output**
Clean verify passes from an empty database. CI green. Postman collection works end to end without manually pasting tokens. Tag pushed.

**⏱️ Estimated Time**
1 hour.

---

## 📅 Suggested Daily Schedule

**Day 1** *(4 hours)* — Entities and repositories
- Task 1 — Five IAM entity classes (2.25h)
- Task 2 — Five repositories (1.25h)
- *Start Task 3 if time allows*

**Day 2** *(3.5 hours)* — Prove the mapping, start isolation
- Task 3 — Entity–schema mapping smoke test (1.25h)
- Task 4 — `TenantContext` holder (1.25h)
- *Begin Task 5*

**Day 3** *(4 hours)* — Tenant isolation
- Task 5 — `@TenantId` + `CurrentTenantIdentifierResolver` (2.75h)
- Task 6 — Tenant isolation unit test (1.5h)

*Days 2–3 are the hardest stretch in the phase and contain the least visible progress — no endpoint works yet. **Do not skip ahead to the fun auth endpoints.** Every endpoint written before the filter exists has to be re-verified afterwards.*

**Day 4** *(3.5 hours)* — JWT plumbing
- Task 7 — `JwtService` (2h)
- Task 8 — `JwtAuthenticationFilter` (2h)

**Day 5** *(3.5 hours)* — Security wiring
- Task 9 — `TenantAwareUserDetailsService` (1.25h)
- Task 10 — `SecurityConfig` (1.75h)

**Day 6** *(3.75 hours)* — First working endpoints
- Task 11 — `POST /auth/register` (1.75h)
- Task 12 — `POST /auth/login` (2h)

*End of Day 6 is the first moment the phase feels real: you can register and log in through Postman.*

**Day 7** *(4 hours)* — Refresh tokens
- Task 13 — `RefreshTokenService` with rotation and reuse detection (2.75h)
- Task 14 — `/auth/refresh` and `/auth/logout` (1.5h)

**Day 8** *(3.5 hours)* — Finish the surface
- Task 15 — `GET /auth/me` (1.25h)
- Task 16 — `@PreAuthorize` on the service layer (1.5h)

**Day 9** *(4 hours)* — Seeding and the security tests
- Task 17 — IAM seed loader (2h)
- Task 18 — Refresh rotation and reuse tests (1.75h)

**Day 10** *(3.5 hours)* — The proof, and close
- Task 19 — Parameterised cross-tenant test (2.25h)
- Task 20 — Clean up, verify, tag (1h)

**Day 11 — Buffer / Catch-up**
Reserved. Most likely consumed by Task 5 (Hibernate multi-tenancy registration has unhelpful error messages) or Task 13 (reuse detection has more edge cases than it first appears). If neither bites, use it to start [06 §11](06-UI-UX-DESIGN.md) Tier 1 — the Login and Register screens now have working endpoints to build against.

---

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
PHASE SUMMARY
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Total Tasks    : 20
Total Estimate : 26–30 hours
Suggested Days : 10 working days + 1 buffer
                 (NOT the 4–5 days in doc 07 — see the correction
                  at the top of this document)

Hardest Task   : Task 13 — RefreshTokenService with reuse detection.
                 Entirely new work with nothing to port, security-critical,
                 and the edge cases are not obvious: concurrent refresh,
                 family revocation ordering, and the fact that you cannot
                 tell the attacker from the victim — which is precisely
                 why you revoke both.

Most Skipped   : Task 6 — the tenant isolation unit test.
                 Task 5 appears to work the moment a list query returns
                 the right rows, so it is tempting to move on. The
                 assertion people skip is the one that matters: what
                 happens when TenantContext is UNSET. The default failure
                 mode of a badly-built tenant filter is that an unset
                 context silently disables filtering — which means every
                 background worker in Phase 6 would read every tenant.
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

---

## Phase 4 exit checklist

Tick all eight before starting Phase 5:

- [ ] `docker compose down -v && docker compose up -d && mvn clean verify` passes from empty
- [ ] Register → login → authenticated request works in Postman for all four roles
- [ ] Refresh rotates the pair; **replaying a used token revokes the whole family**
- [ ] An `AGENT` calling an admin-only method gets `403`; unauthenticated gets `401`; both in problem+json
- [ ] `GET /auth/me` returns the correct permissions array per role
- [ ] **The cross-tenant test covers every existing endpoint and you have watched it fail on purpose**
- [ ] Seed data loads idempotently and prints the login table
- [ ] Every log line carries `tenantId` and `userId` in the MDC

---

## Next Steps

> ✅ **Task list ready for Phase 4 — Core Authentication & User Management!**
>
> **Work the tasks in order.** The ordering is load-bearing in one specific place: **tenant isolation (Tasks 4–6) comes before any endpoint.** It is tempting to build login first because it is more satisfying, but every endpoint written before the filter exists has to be re-verified against it afterwards, and the one you forget to re-verify is the leak.
>
> **Two things to carry into Phase 5:**
> 1. Every new entity gets `@TenantId` on its `tenantId` field. Add it at creation time, not later.
> 2. Every new endpoint gets a line in Task 19's `@MethodSource`. The list grows from ~6 entries to ~52 by Phase 8, and that growing list is the evidence behind your tenant-isolation claim.
>
> **When Phase 4 is complete:**
> Run the `task-breakdown` skill again on **Phase 5 — Core MVP: Ticketing & the SLA Engine**, using the phase definition in [07](07-DEV-PHASES.md). It is the largest phase in the project (8–11 days as estimated, and worth re-checking against a task breakdown the way this one was), so do not start it from the phase description alone.
