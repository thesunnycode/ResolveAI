// Phase 2 Task 14 — structural verification of docs/openapi.yaml.
//
// Not a full OpenAPI 3.1 JSON-Schema validation; it checks the things that actually
// break a hand-written spec:
//
//   1. it parses as YAML at all
//   2. every internal $ref resolves  ← the one that catches real mistakes
//   3. every operation has an operationId, and they are unique
//   4. every operation has at least one response
//   5. every tag used is declared
//   6. the twelve endpoints that were promised full schemas actually have examples
//
// Run:  node ops/verify-openapi.mjs
import { readFileSync } from 'node:fs';
import { parse } from '../seed/generator/node_modules/yaml/dist/index.js';

const METHODS = ['get', 'put', 'post', 'delete', 'patch', 'options', 'head', 'trace'];

// The twelve promised in doc 16 Task 14, by operationId.
const FULLY_SPECIFIED = [
  'login', 'refreshToken', 'getCurrentUser', 'createTicket', 'listTickets', 'getTicket',
  'addMessage', 'assignTicket', 'changeStatus', 'getAnalysis', 'getDraft', 'listIncidents',
];

let failures = 0;
const fail = (msg) => { console.log(`  ❌ ${msg}`); failures++; };
const ok = (label, detail) => console.log(`  ✅ ${label.padEnd(36)} ${detail}`);

const doc = parse(readFileSync(new URL('../docs/openapi.yaml', import.meta.url), 'utf8'));

// ── 1. shape ────────────────────────────────────────────────────────────────
console.log('── document ──');
if (doc.openapi !== '3.1.0') fail(`openapi is ${doc.openapi}, expected 3.1.0`);
else ok('openapi version', doc.openapi);
if (!doc.info?.title || !doc.info?.version) fail('info.title / info.version missing');

// ── 2. every $ref resolves ──────────────────────────────────────────────────
const refs = [];
(function walk(node, path) {
  if (Array.isArray(node)) return node.forEach((v, i) => walk(v, `${path}[${i}]`));
  if (node && typeof node === 'object') {
    for (const [k, v] of Object.entries(node)) {
      if (k === '$ref' && typeof v === 'string') refs.push({ ref: v, at: path });
      else walk(v, `${path}/${k}`);
    }
  }
})(doc, '');

const resolve = (ref) => {
  if (!ref.startsWith('#/')) return true;              // external refs are out of scope
  let cur = doc;
  for (const seg of ref.slice(2).split('/')) {
    cur = cur?.[seg.replace(/~1/g, '/').replace(/~0/g, '~')];
    if (cur === undefined) return false;
  }
  return true;
};
const broken = refs.filter((r) => !resolve(r.ref));
if (broken.length) broken.forEach((b) => fail(`unresolved $ref ${b.ref} at ${b.at}`));
else ok('$refs resolve', `${refs.length}/${refs.length}`);

// ── 3. operations ───────────────────────────────────────────────────────────
console.log('── operations ──');
const ops = [];
for (const [p, item] of Object.entries(doc.paths ?? {}))
  for (const m of METHODS) if (item[m]) ops.push({ path: p, method: m, op: item[m] });

ok('paths', String(Object.keys(doc.paths ?? {}).length));
ok('operations', String(ops.length));

const ids = ops.map((o) => o.op.operationId).filter(Boolean);
if (ids.length !== ops.length)
  ops.filter((o) => !o.op.operationId).forEach((o) => fail(`no operationId: ${o.method.toUpperCase()} ${o.path}`));
const dupes = ids.filter((id, i) => ids.indexOf(id) !== i);
if (dupes.length) fail(`duplicate operationIds: ${[...new Set(dupes)].join(', ')}`);
else ok('operationIds unique', String(ids.length));

for (const { path, method, op } of ops) {
  if (!op.summary) fail(`no summary: ${method.toUpperCase()} ${path}`);
  if (!op.responses || !Object.keys(op.responses).length)
    fail(`no responses: ${method.toUpperCase()} ${path}`);
  for (const [code, res] of Object.entries(op.responses ?? {}))
    if (!res.$ref && !res.description) fail(`response ${code} has no description: ${method.toUpperCase()} ${path}`);
}
ok('summaries and responses', 'present on every operation');

// ── 4. tags ─────────────────────────────────────────────────────────────────
const declared = new Set((doc.tags ?? []).map((t) => t.name));
const used = new Set(ops.flatMap((o) => o.op.tags ?? []));
const undeclared = [...used].filter((t) => !declared.has(t));
if (undeclared.length) fail(`tags used but not declared: ${undeclared.join(', ')}`);
else ok('tags declared', `${used.size} used, ${declared.size} declared`);

// ── 5. the twelve that were promised full schemas ───────────────────────────
console.log('── fully specified endpoints ──');
const hasExample = (op) => JSON.stringify(op).includes('"example') && /"(example|examples)":/.test(JSON.stringify(op));
const byId = Object.fromEntries(ops.map((o) => [o.op.operationId, o]));
let full = 0;
for (const id of FULLY_SPECIFIED) {
  const entry = byId[id];
  if (!entry) { fail(`promised operation missing: ${id}`); continue; }
  if (!hasExample(entry.op)) fail(`no example on promised operation: ${id}`);
  else full++;
}
ok('with realistic examples', `${full}/${FULLY_SPECIFIED.length}`);

// ── 6. coverage of the TODO markers, for information ────────────────────────
const todo = ops.filter((o) => /TODO: schema/.test(o.op.description ?? '')).length;
ok('still carrying TODO: schema', `${todo} of ${ops.length}`);

console.log();
if (failures) { console.log(`❌ ${failures} problem(s)`); process.exitCode = 1; }
else console.log('✅ openapi.yaml verified');
