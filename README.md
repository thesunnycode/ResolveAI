<h1 align="center">ResolveAI</h1>

<p align="center">
  An AI-assisted helpdesk platform where the model handles language<br>
  and deterministic policy handles every decision that actually matters.
</p>

<p align="center"><sub><b>v1.0</b> — all 10 phases complete</sub></p>

<p align="center">
  <img src="https://skillicons.dev/icons?i=java,spring,react,ts,postgres,redis,docker&perline=7" alt="Java, Spring Boot, React, TypeScript, PostgreSQL, Redis, Docker" />
</p>

<br>

### 🎫 Triage · SLA · Incidents · AI drafting

A ticket comes in as freeform text. The AI reads it and emits structured
signals — category, urgency indicators, mentioned entities. A pure-function
priority policy turns those signals into a P1–P4 decision with a full rule
trace. The SLA clock starts, routed to the right team, and the agent gets a
cited draft when they open the ticket. If forty tickets arrive about the
same payment outage in twenty minutes, they collapse into one incident
instead of forty parallel investigations.

<table align="center">
<tr>
<td align="center"><b>75</b><br><sub>API endpoints</sub></td>
<td align="center"><b>616</b><br><sub>tests</sub></td>
<td align="center"><b>39</b><br><sub>DB tables</sub></td>
<td align="center"><b>Java 21</b><br><sub>Spring Boot 4</sub></td>
</tr>
</table>

<br>

**Live demo:** [resolveai.thesunnycode.me](https://resolveai.thesunnycode.me) — one-click sign-in as Agent, Team Lead, Customer, or Admin, no account needed.

<br>

### What it does

- **AI triage** — every ticket is redacted, embedded, classified and routed before an agent sees it. The model returns signals; `PriorityPolicy` (a pure function, 45 tests, no network) computes the priority through 8 named rules
- **SLA engine** — business-hours arithmetic against per-tenant calendars, clocks stored as append-only segments, deadlines polled with `SELECT … FOR UPDATE SKIP LOCKED` so the poller survives restarts
- **Incident correlation** — tickets cluster by embedding similarity + entity overlap; a statistical gate (cluster size ≥ K *and* arrival rate > 3× baseline) decides if an incident exists. The model writes the title; the math decides whether it's real
- **Citation-enforced drafts** — resolution drafts are individually-cited claims. Numeric facts not in the cited span are dropped deterministically; anything below 0.8 entailment coverage is suppressed entirely rather than shown
- **Multi-tenancy** — `@TenantId` on every entity, JWT carries the tenant, a ThreadLocal resolver appends the discriminator to every query
- **Transactional outbox** — ticket and its triage job commit in the same transaction. No broker needed at this scale
- **Demo mode** — one-click sign-in, auto-seeded showcase tickets, a "Simulate payment outage" button that fires 38 realistic tickets and triggers the incident pipeline live

<br>

### Stack

| Layer | Technology |
|---|---|
| Language | Java 21 (virtual threads) |
| Framework | Spring Boot 4.1 · Spring AI 2.0 · Spring Security |
| Database | PostgreSQL 16 + pgvector + tsvector |
| Cache | Redis 7 (token storage, idempotency, rate limiting) |
| Migrations | Flyway — V1 through V18 |
| AI | OpenAI gpt-4.1-mini (primary) · llama3.2:3b local fallback via Ollama |
| Frontend | React 18 · TypeScript · Vite · shadcn/ui |
| Infra | Docker Compose (local) · Heroku container stack (backend) · Vercel (frontend) |
| CI | GitHub Actions |

<br>

### Quick start (local)

You'll need JDK 21, Maven 3.9+, Docker Desktop (≥6 GB), and Node 20+.

```bash
git clone https://github.com/thesunnycode/ResolveAI.git
cd ResolveAI

cp .env.example .env
# fill in OPENAI_API_KEY and any SMTP settings — everything else has local defaults

docker compose up -d
./mvnw spring-boot:run          # backend on http://localhost:8080
npm --prefix frontend run dev   # frontend on http://localhost:5176
```

The backend seeds 3 tenants, 12 teams and 87 users on first start.
Login password for every seeded account: `resolveai-local-2026`

| Tenant | Role | Email |
|---|---|---|
| acme | ADMIN | admin@acme.com |
| acme | TEAM_LEAD | sana@acme.com |
| acme | AGENT | arjun@acme.com |
| acme | CUSTOMER | customer1@example.com |

Health check: `curl http://localhost:8080/actuator/health`

<br>

### Three engineering decisions worth reading about

<details>
<summary><b>Signals from the model, decisions from the policy</b></summary>
<br>

`TriageSignals` has no priority field — by design. The model reports what a
ticket *says*: its category, whether it claims an outage, whether money is
involved. `PriorityPolicy` — a pure function with no Spring, no JPA, no
network call — turns those observations into a P1–P4 decision through 8
named, ordered, versioned rules and returns the full trace of what matched
and what didn't.

`GET /tickets/{id}/priority-rationale` exposes both halves — model
observations and policy facts — so when an agent overrides a decision you
can see whether the model misread the ticket or the policy is wrong. Two
different bugs, fixed two different ways.
</details>

<details>
<summary><b>The SLA clock that can't lose time</b></summary>
<br>

There's no `elapsed_minutes` column. Elapsed time is a `SUM` over
`sla_clock_segment` rows, derived on every read. Pausing closes the open
segment; resuming opens a new one and recomputes the deadline from *remaining
budget*, not the original start — a ticket that spent two days waiting on
the customer has burned none of its target.

A mutable counter is a lost update under concurrency, and a wrong one is
undetectable after the fact. A segment history can always be re-derived.
The database enforces the shape: `uq_segment_open` allows one open segment
per record, and a trigger refuses to reopen a closed one.

Escalation rungs are exactly-once because of one line of DDL:
`UNIQUE (sla_record_id, rung)`. The poller is at-least-once by construction;
the constraint makes a duplicate fire a no-op instead of a second
notification.
</details>

<details>
<summary><b>Incident correlation without a single model vote</b></summary>
<br>

No rule clusters *"card declined"* with *"UPI not going through"* —
they share almost no vocabulary. Leader clustering over ticket embeddings
does. But the model has no say in whether an incident *exists*: that
requires cluster size ≥ K **and** arrival rate > 3× the per-weekday-per-hour
baseline stored in a materialized view.

The gate's four thresholds are all returned with the verdict, so the board
card reads *"38 tickets · 12.6× baseline · gate: ≥5 AND >3.0× — both
passed"* rather than asking anyone to trust it. `IncidentTitleGenerator`
is the model's entire contribution: a title and a two-sentence summary, with
a template fallback that means the feature works with the AI provider off.
</details>

<br>

### API overview

<details>
<summary>75 endpoints across auth, tickets, SLA, triage, knowledge, drafts, incidents, AI policy, analytics, and demo — click to expand</summary>

| Area | Methods | Endpoint prefix | Auth |
|---|---|---|---|
| Auth | POST | `/api/v1/auth/register` · `/login` · `/refresh` · `/logout` · `/me` | Public / JWT |
| Auth | POST | `/api/v1/auth/forgot-password` · `/reset-password` · `/change-password` | Public / JWT |
| Invites | POST, GET | `/api/v1/invites` · `/api/v1/invites/{token}` | Admin / Public |
| Agents | GET, PUT | `/api/v1/agents/me/availability` · `/api/v1/admin/agents/{id}/capacity` | Agent / Admin |
| Teams | GET, POST, DELETE | `/api/v1/admin/teams` | Admin |
| Tickets | POST, GET, PATCH | `/api/v1/tickets` · `/api/v1/tickets/{id}` | JWT (role-scoped) |
| Tickets | POST | `/assign` · `/status` · `/resolve` · `/reopen` · `/messages` | Agent / Team Lead |
| Triage | GET, POST | `/api/v1/tickets/{id}/analysis` · `/priority-rationale` · `/priority-override` · `/retriage` | Team Lead / Admin |
| SLA | GET, POST | `/api/v1/tickets/{id}/sla` · `/pause` · `/resume` · `/at-risk` | JWT |
| SLA Admin | GET, POST | `/api/v1/admin/sla-policies` · `/calendars` | Admin |
| Knowledge | GET, POST, PUT, DELETE | `/api/v1/knowledge/documents` | Team Lead / Admin |
| Drafts | POST, GET | `/api/v1/tickets/{id}/drafts` · `/api/v1/drafts/{id}` · `/action` | Agent+ |
| Incidents | GET, POST | `/api/v1/incidents` · `/{id}` · `/confirm` · `/reject` · `/resolve` · `/link` · `/updates` | Team Lead+ |
| AI Policy | GET, PUT | `/api/v1/admin/ai-policy` · `/ai-usage` | Admin |
| Analytics | GET | `/api/v1/admin/analytics/funnel` | Admin |
| Eval | GET, POST | `/api/v1/admin/eval/runs` | Admin |
| Demo | POST | `/api/v1/demo` · `/demo/storm` | Public |
| Health | GET | `/actuator/health` · `/actuator/prometheus` | Public / Scraper |

Full request/response shapes: [`docs/openapi.yaml`](docs/openapi.yaml) or the Redocly viewer.

**Key environment variables** (see `.env.example` for the full list):

| Variable | Required | Notes |
|---|---|---|
| `DATABASE_URL` | Yes | PostgreSQL connection string |
| `REDIS_URL` | Yes | Redis connection string |
| `JWT_SECRET` | Yes | HS256 signing key, 32+ bytes |
| `OPENAI_API_KEY` | Yes (for AI features) | gpt-4.1-mini for triage + drafting |
| `CORS_ALLOWED_ORIGINS` | Prod only | Vercel frontend URL |
| `DEMO_ENABLED` | No | `true` to enable one-click demo logins |
| `SMTP_HOST` / `SMTP_USER` / `SMTP_PASS` | No | Blank = emails log to console |

</details>

<br>

### Deployment

Backend is on **Heroku** (container stack, `heroku.yml`).
Frontend is on **Vercel** — `vercel.json` proxies `/api/*` to the Heroku backend
so the browser never makes a cross-origin request and no CORS entry is needed for it.
Database on **Neon** (pgvector confirmed), cache on **Upstash** (Redis TLS).

```bash
# push backend to Heroku
git push heroku main

# frontend deploys automatically on push to main via Vercel GitHub integration
```

<br>

### Running verification scripts

```bash
bash ops/verify-stack.sh         # 7 containers, pgvector, HNSW index
bash ops/verify-migrations.sh    # schema from empty + 10 structural guarantees
bash ops/verify-openapi.sh       # Redocly lint + 75 operations
node ops/verify-postman.mjs      # Postman collection vs OpenAPI contract
./mvnw clean verify              # 616 tests (includes Testcontainers suites)
```

<br>

### License

MIT © 2026 Sunny Kr Singh
