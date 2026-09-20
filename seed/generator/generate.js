#!/usr/bin/env node
/**
 * ResolveAI corpus generator — Phase 1, Task 9.
 *
 *   node generate.js --count 200 --seed 42 --out ../generated/tickets-starter.json
 *   node generate.js --count 10 --seed 1 --dry-run     # structure only, no LLM
 *
 * DESIGN: label-first. Category, priority, team, persona, entities, timestamps
 * and resolution duration are all decided deterministically in structure.js
 * BEFORE the model is asked for anything. The LLM supplies phrasing only.
 * That gives ground truth by construction — N labelled examples for the price
 * of one generation run.
 */

import { writeFileSync, mkdirSync, existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadDomain } from './lib/domain.js';
import { makeRng } from './lib/rng.js';
import { pickStructure, generateArrivalTimes } from './lib/structure.js';
import { resolveConfig, generateBatch, extractJsonArray } from './lib/llm.js';

const HERE = dirname(fileURLToPath(import.meta.url));

// ── args ────────────────────────────────────────────────────────────────────
const argv = process.argv.slice(2);
const arg = (name, dflt) => {
  const i = argv.indexOf(`--${name}`);
  return i === -1 ? dflt : argv[i + 1];
};
const flag = (name) => argv.includes(`--${name}`);

const COUNT = parseInt(arg('count', '200'), 10);
const SEED = parseInt(arg('seed', '42'), 10);
const DAYS = parseInt(arg('days', '14'), 10);
const BATCH = parseInt(arg('batch', '20'), 10);
const DRY_RUN = flag('dry-run');
const NO_CACHE = flag('no-cache');
const OUT = resolve(HERE, arg('out', '../generated/tickets-starter.json'));

// .env is at the repo root; load it without a dependency.
const envPath = resolve(HERE, '..', '..', '.env');
if (existsSync(envPath)) {
  for (const line of readFileSync(envPath, 'utf8').split('\n')) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/);
    if (m && !process.env[m[1]]) process.env[m[1]] = m[2].replace(/^["']|["']$/g, '');
  }
}

// ── prompts ─────────────────────────────────────────────────────────────────

const SYSTEM = `You write realistic customer support tickets for Ledgerly, a B2B invoicing
and payments SaaS used by Indian small and medium businesses. Customers raise GST invoices,
collect payment by UPI/card/netbanking, reconcile against a ledger, and sync to Tally or
Zoho Books.

You will be given a JSON array of scenarios. For EACH scenario, write one ticket.

Return ONLY a JSON array, same length and order as the input, each element:
  { "subject": "...", "body": "..." }

RULES — these are not stylistic preferences, they are requirements:

1. NEVER use the scenario's forbiddenWords (or an obvious morphological variant) as
   standalone words anywhere in the subject or body. Describe the SYMPTOM as the
   customer experienced it. If the category name appears, classification degenerates
   into keyword matching and the whole corpus is worthless.

   The ONE exception: a service name from "entities" is used verbatim even if it
   contains a forbidden substring — "payment-service" is fine, a bare "payment" is not.

   Instead of the forbidden word, reach for the symptom:
     PAYMENT → "money left my account", "card was declined", "collect request expired"
     BILLING → "charged for a plan I downgraded", "subscription renewed twice"
     AUTH    → "logged out constantly", "reset link never arrives", "2FA code rejected"
     API     → "endpoint returns 429", "webhook never fired", "SDK throws on init"
     DATA    → "export is missing rows", "return does not tally", "records duplicated"

2. Match the persona exactly:
   - OWNER: non-technical, often frustrated or worried, describes symptoms not causes,
     sometimes wrong about the cause, occasional Hinglish ("kya karu", "bahut urgent").
     Never uses error codes. Often one long run-on sentence.
   - ACCOUNTANT: precise about amounts, dates, invoice numbers, GST periods. Calm,
     slightly formal. Cares about reconciliation being correct.
   - DEVELOPER: terse, technical. Includes error codes, HTTP statuses, timestamps,
     request IDs. Writes in fragments. No pleasantries.

3. Use EVERY entity listed in the scenario's "entities" object, verbatim. If the object
   is empty, mention no service name, no error code and no invoice reference at all —
   a vague ticket is realistic and roughly half of them must be vague.

4. Vary length hard: some tickets 12 words, some 150. Do not write uniform paragraphs.

5. Match the urgency implied by the priority without ever naming it:
   P1 reads as business-stopping, P4 reads as a mild annoyance or a question.

6. Subject: max 90 characters, lowercase-ish, the way a real person types it. Not a
   well-formed headline.`;

function buildUserPrompt(scenarios, domain) {
  const payload = scenarios.map((s, i) => ({
    i,
    persona: s.persona,
    priority: s.priority,
    forbiddenWords: forbiddenWordsFor(s.category),
    situation: domain.descriptionFor(s.category),
    entities: s.entities,
  }));
  return `Write ${scenarios.length} tickets for these scenarios.\n\n` +
    JSON.stringify(payload, null, 2) +
    `\n\nReturn ONLY the JSON array of {subject, body}, ${scenarios.length} elements, in order.`;
}

// Rule 1 enforcement input — and the post-generation check below.
const FORBIDDEN = {
  PAYMENT:     ['payment', 'paid', 'paying', 'pay '],
  BILLING:     ['billing', 'bill '],
  AUTH:        ['auth', 'authentication'],
  API:         ['api '],
  PERFORMANCE: ['performance'],
  INTEGRATION: ['integration', 'integrate'],
  DATA:        ['data '],
  ONBOARDING:  ['onboarding', 'onboard'],
};
const forbiddenWordsFor = (cat) => FORBIDDEN[cat] ?? [];

// ── main ────────────────────────────────────────────────────────────────────

async function main() {
  const domain = loadDomain();
  const rng = makeRng(SEED);

  console.log(`ResolveAI corpus generator`);
  console.log(`  count=${COUNT} seed=${SEED} days=${DAYS} batch=${BATCH}${DRY_RUN ? ' [DRY RUN]' : ''}`);
  console.log(`  categories=${domain.categories.length} tenants=${domain.tenants.length}\n`);

  // 1. Structure first — this is the ground truth.
  const structures = Array.from({ length: COUNT }, () => pickStructure(rng, domain));
  const arrivals = generateArrivalTimes(rng, COUNT, DAYS);
  structures.forEach((s, i) => { s.createdAt = arrivals[i]; });

  if (DRY_RUN) {
    report(structures, domain);
    console.log('\nDry run — no LLM calls made, nothing written.');
    console.log('Sample structures:\n');
    console.log(JSON.stringify(structures.slice(0, 3), null, 2));
    return;
  }

  // 2. Phrasing second.
  const config = resolveConfig();
  console.log(`  provider=${config.provider} model=${config.model}\n`);

  const tickets = [];
  let tokensIn = 0, tokensOut = 0, cachedBatches = 0, violations = 0;

  for (let start = 0; start < structures.length; start += BATCH) {
    const chunk = structures.slice(start, start + BATCH);
    const n = Math.floor(start / BATCH) + 1;
    const total = Math.ceil(structures.length / BATCH);
    process.stdout.write(`  batch ${n}/${total} (${chunk.length}) … `);

    const res = await generateBatch(config, SYSTEM, buildUserPrompt(chunk, domain), {
      maxTokens: Math.min(8192, 420 * chunk.length),
      noCache: NO_CACHE,
    });
    tokensIn += res.usage.in; tokensOut += res.usage.out;
    if (res.cached) cachedBatches++;

    const written = extractJsonArray(res.text);
    if (written.length !== chunk.length) {
      throw new Error(`Batch ${n}: asked for ${chunk.length} tickets, got ${written.length}`);
    }

    chunk.forEach((s, i) => {
      const t = assemble(s, written[i], domain, start + i);
      if (t._violations.length) violations++;
      tickets.push(t);
    });
    console.log(res.cached ? 'cached' : `${res.usage.out} tok`);
  }

  // 3. Report and write.
  report(structures, domain);
  console.log(`\n  tokens: ${tokensIn} in / ${tokensOut} out   (${cachedBatches} batches from cache)`);
  if (violations) {
    console.log(`  ⚠  ${violations}/${tickets.length} tickets leaked a forbidden category word — see _violations`);
  } else {
    console.log(`  ✅ no forbidden-word violations`);
  }

  mkdirSync(dirname(OUT), { recursive: true });
  writeFileSync(OUT, JSON.stringify({
    meta: {
      generatedAt: new Date().toISOString(),
      generator: 'seed/generator/generate.js',
      seed: SEED, count: COUNT, days: DAYS,
      provider: config.provider, model: config.model,
      note: 'Synthetic. Labels are ground truth by construction — structure was ' +
            'generated first, text second. See docs/fictional-product.md.',
    },
    tickets,
  }, null, 2));
  console.log(`\n  → ${OUT}`);
}

function assemble(s, written, domain, index) {
  const subject = String(written.subject ?? '').trim().slice(0, 200);
  const body = String(written.body ?? '').trim();
  // Mask entity values before scanning. The model is REQUIRED to use them verbatim,
  // and "payment-service" legitimately contains "payment". A naive substring match
  // reported 11 false positives out of 19 on the first run — the check was wrong,
  // not the model.
  let haystack = `${subject}\n${body}`.toLowerCase();
  for (const v of Object.values(s.entities)) {
    haystack = haystack.split(String(v).toLowerCase()).join(' ⟪ entity ⟫ ');
  }
  const violations = forbiddenWordsFor(s.category).filter((w) => haystack.includes(w));

  const createdAt = s.createdAt;
  const resolvedAt = new Date(createdAt.getTime() + s.resolutionBusinessMinutes * 60_000);

  return {
    externalId: `GEN-${String(index + 1).padStart(5, '0')}`,
    tenantSlug: s.tenant.slug,
    subject,
    body,
    // ── ground truth ──
    category: s.category,
    priority: s.priority,
    team: s.team,
    persona: s.persona,
    entities: s.entities,
    // ── timing ──
    createdAt: createdAt.toISOString(),
    resolutionBusinessMinutes: s.resolutionBusinessMinutes,
    resolvedAtApprox: resolvedAt.toISOString(),
    _violations: violations,
  };
}

function report(structures, domain) {
  const count = (fn) => structures.reduce((m, s) => (m[fn(s)] = (m[fn(s)] || 0) + 1, m), {});
  const pct = (n) => `${((n / structures.length) * 100).toFixed(1)}%`;
  const show = (label, map) => {
    console.log(`  ${label}:`);
    Object.entries(map).sort((a, b) => b[1] - a[1])
      .forEach(([k, v]) => console.log(`    ${k.padEnd(16)} ${String(v).padStart(4)}  ${pct(v)}`));
  };
  console.log('\n── distribution ──');
  show('category', count((s) => s.category));
  show('priority', count((s) => s.priority));
  show('persona', count((s) => s.persona));
  show('tenant', count((s) => s.tenant.slug));
  const withEntities = structures.filter((s) => Object.keys(s.entities).length).length;
  console.log(`  entities present:  ${withEntities}/${structures.length} (${pct(withEntities)})  — target ~50%`);
}

main().catch((err) => {
  console.error(`\n❌ ${err.message}`);
  process.exit(1);
});
