// Phase 2 Task 15 — the Postman collection actually matches the contract.
//
// A collection drifts from its spec silently: an endpoint gets added to openapi.yaml,
// nobody adds the request, and six months later the "complete" collection is missing
// the thing you need. This checks:
//
//   1. both files are valid JSON
//   2. every openapi operation has a request, matched on method + normalised path
//   3. no request targets a path the spec does not define (a typo, or a stale request)
//   4. login stores accessToken and refreshToken
//   5. the Smoke Test folder exists and has the eight requests doc 13 T17 expects
//   6. NO SECRETS in the committed environment  ← the one that matters most
//
// Run:  node ops/verify-postman.mjs
import { readFileSync } from 'node:fs';
import { parse } from '../seed/generator/node_modules/yaml/dist/index.js';

const METHODS = ['get', 'put', 'post', 'delete', 'patch'];
const here = (p) => new URL(p, import.meta.url);

let failures = 0;
const fail = (m) => { console.log(`  ❌ ${m}`); failures++; };
const ok = (label, detail) => console.log(`  ✅ ${label.padEnd(36)} ${detail}`);

const spec = parse(readFileSync(here('../docs/openapi.yaml'), 'utf8'));
const coll = JSON.parse(readFileSync(here('../ops/postman/ResolveAI.postman_collection.json'), 'utf8'));
const env = JSON.parse(readFileSync(here('../ops/postman/ResolveAI.local.postman_environment.json'), 'utf8'));

// A path with every variable segment collapsed, so `/tickets/{{ticketId}}/sla` and
// `/tickets/{id}/sla` compare equal.
const normalise = (segments) =>
  '/' + segments
    .filter((s) => s && s !== '{{baseUrl}}')
    .map((s) => (/^\{\{.+\}\}$/.test(s) || /^\{.+\}$/.test(s) ? ':v' : s))
    .join('/');

// ── walk the collection ─────────────────────────────────────────────────────
const requests = [];
(function walk(items, folder) {
  for (const it of items ?? []) {
    if (it.item) walk(it.item, it.name);
    else if (it.request) requests.push({ ...it, folder });
  }
})(coll.item);

console.log('── collection ──');
ok('folders', String(coll.item.length));
ok('requests', String(requests.length));

// ── 2 & 3. coverage both ways ───────────────────────────────────────────────
console.log('── coverage against openapi.yaml ──');

const specOps = new Set();
for (const [p, item] of Object.entries(spec.paths ?? {}))
  for (const m of METHODS) if (item[m]) specOps.add(`${m.toUpperCase()} ${normalise(p.split('/'))}`);

const collOps = new Set();
for (const r of requests) {
  const segs = r.request.url?.path ?? [];
  // Actuator is deliberately outside /api/v1 and has its own absolute {{healthUrl}},
  // so it is not in the spec and must not be counted against it.
  if (!segs.length || (r.request.url?.host ?? []).includes('{{healthUrl}}')) continue;
  collOps.add(`${r.request.method} ${normalise(segs)}`);
}

const missing = [...specOps].filter((o) => !collOps.has(o)).sort();
const extra = [...collOps].filter((o) => !specOps.has(o)).sort();

if (missing.length) missing.forEach((o) => fail(`no request for ${o}`));
else ok('every operation has a request', `${specOps.size}/${specOps.size}`);

if (extra.length) extra.forEach((o) => fail(`request targets an undefined path: ${o}`));
else ok('no requests off-contract', `${collOps.size} distinct`);

// ── 4. login self-authenticates ─────────────────────────────────────────────
console.log('── behaviour ──');
const scriptOf = (r) =>
  (r.event ?? []).flatMap((e) => e.script?.exec ?? []).join('\n');

const login = requests.find((r) => r.name === 'Login' && r.folder === 'Auth');
if (!login) fail('Auth › Login not found');
else {
  const s = scriptOf(login);
  const stores = ["pm.environment.set('accessToken'", "pm.environment.set('refreshToken'"]
    .filter((frag) => s.includes(frag));
  if (stores.length === 2) ok('login stores both tokens', 'accessToken + refreshToken');
  else fail('login does not store both tokens — the collection will need manual pasting');
}

if (coll.auth?.bearer?.[0]?.value === '{{accessToken}}')
  ok('collection-level bearer auth', '{{accessToken}}');
else fail('collection-level bearer auth not wired to {{accessToken}}');

const preRequest = (coll.event ?? []).find((e) => e.listen === 'prerequest');
if (preRequest && scriptOf({ event: [preRequest] }).includes('idempotencyKey'))
  ok('pre-request UUID', 'idempotencyKey generated per send');
else fail('no collection-level idempotencyKey generator');

// ── 5. smoke test ───────────────────────────────────────────────────────────
const smoke = coll.item.find((f) => f.name === 'Smoke Test');
if (!smoke) fail('Smoke Test folder missing');
else if (smoke.item.length !== 8) fail(`Smoke Test has ${smoke.item.length} requests, doc 13 T17 expects 8`);
else ok('smoke test', `${smoke.item.length} requests`);

const withoutAssertions = requests.filter((r) => !scriptOf(r).includes('pm.test('));
ok('requests with assertions', `${requests.length - withoutAssertions.length}/${requests.length}`);

// ── 6. no secrets committed ─────────────────────────────────────────────────
console.log('── secrets ──');
const SECRET_KEYS = /password|token|secret|apikey|api_key/i;
const leaked = (env.values ?? []).filter((v) => SECRET_KEYS.test(v.key) && String(v.value ?? '').trim() !== '');
if (leaked.length) leaked.forEach((v) => fail(`environment template has a value for '${v.key}' — must be blank`));
else ok('environment template is blank', `${(env.values ?? []).filter((v) => SECRET_KEYS.test(v.key)).length} secret keys, all empty`);

// Belt and braces: an OpenAI-style key or a JWT anywhere in either file.
for (const [label, text] of [
  ['collection', JSON.stringify(coll)],
  ['environment', JSON.stringify(env)],
]) {
  if (/sk-[A-Za-z0-9_-]{20,}/.test(text)) fail(`${label} contains something shaped like an API key`);
  if (/eyJ[A-Za-z0-9_-]{30,}\.[A-Za-z0-9_-]{20,}\./.test(text)) fail(`${label} contains something shaped like a real JWT`);
}

console.log();
if (failures) { console.log(`❌ ${failures} problem(s)`); process.exitCode = 1; }
else console.log('✅ postman collection verified');
