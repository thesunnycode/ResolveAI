// Provider-agnostic LLM client for corpus generation ONLY.
// The application uses Spring AI; this is a standalone offline script.
//
// Generation is batched (20 scenarios per call) and cached on disk by content
// hash, so a re-run after a crash costs nothing and `--seed` stays reproducible.

import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const CACHE_DIR = join(HERE, '..', '..', '.cache');

const sha = (s) => createHash('sha256').update(s).digest('hex').slice(0, 32);

function cacheGet(key) {
  const f = join(CACHE_DIR, `${key}.json`);
  return existsSync(f) ? JSON.parse(readFileSync(f, 'utf8')) : null;
}
function cachePut(key, value) {
  mkdirSync(CACHE_DIR, { recursive: true });
  writeFileSync(join(CACHE_DIR, `${key}.json`), JSON.stringify(value, null, 2));
}

export function resolveConfig(env = process.env) {
  const provider = (env.LLM_PRIMARY_PROVIDER || '').toLowerCase();
  const apiKey = env.LLM_PRIMARY_API_KEY;
  const model = env.LLM_PRIMARY_MODEL;
  if (!provider || !apiKey || !model) {
    throw new Error(
      'Missing LLM config. Set LLM_PRIMARY_PROVIDER, LLM_PRIMARY_API_KEY and\n' +
      'LLM_PRIMARY_MODEL in .env (see .env.example). Phase 1 Task 5.\n' +
      'Run with --dry-run to generate structure only, with no LLM calls.'
    );
  }
  if (!['anthropic', 'openai'].includes(provider)) {
    throw new Error(`Unsupported LLM_PRIMARY_PROVIDER "${provider}" (expected anthropic|openai)`);
  }
  return { provider, apiKey, model };
}

async function callAnthropic({ apiKey, model }, system, user, maxTokens) {
  const res = await fetch('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'x-api-key': apiKey,
      'anthropic-version': '2023-06-01',
    },
    body: JSON.stringify({
      model,
      max_tokens: maxTokens,
      temperature: 1,           // variety is the whole point here
      system,
      messages: [{ role: 'user', content: user }],
    }),
  });
  if (!res.ok) throw new Error(`Anthropic ${res.status}: ${(await res.text()).slice(0, 400)}`);
  const json = await res.json();
  return {
    text: json.content.map((c) => c.text ?? '').join(''),
    usage: { in: json.usage?.input_tokens ?? 0, out: json.usage?.output_tokens ?? 0 },
  };
}

async function callOpenAI({ apiKey, model }, system, user, maxTokens) {
  const res = await fetch('https://api.openai.com/v1/chat/completions', {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${apiKey}` },
    body: JSON.stringify({
      model,
      max_tokens: maxTokens,
      temperature: 1,
      messages: [
        { role: 'system', content: system },
        { role: 'user', content: user },
      ],
    }),
  });
  if (!res.ok) throw new Error(`OpenAI ${res.status}: ${(await res.text()).slice(0, 400)}`);
  const json = await res.json();
  return {
    text: json.choices[0].message.content,
    usage: { in: json.usage?.prompt_tokens ?? 0, out: json.usage?.completion_tokens ?? 0 },
  };
}

export async function generateBatch(config, system, user, { maxTokens = 4096, noCache = false } = {}) {
  const key = sha(`${config.provider}|${config.model}|${system}|${user}`);
  if (!noCache) {
    const hit = cacheGet(key);
    if (hit) return { ...hit, cached: true };
  }

  let lastErr;
  for (let attempt = 1; attempt <= 4; attempt++) {
    try {
      const fn = config.provider === 'anthropic' ? callAnthropic : callOpenAI;
      const out = await fn(config, system, user, maxTokens);
      cachePut(key, out);
      return { ...out, cached: false };
    } catch (err) {
      lastErr = err;
      const retriable = /\b(429|500|502|503|504|fetch failed|ETIMEDOUT)\b/i.test(String(err.message));
      if (!retriable || attempt === 4) break;
      const backoff = Math.min(2 ** attempt, 20) * 1000 + Math.random() * 1000;
      process.stderr.write(`  retry ${attempt}/3 in ${Math.round(backoff / 1000)}s — ${err.message.slice(0, 80)}\n`);
      await new Promise((r) => setTimeout(r, backoff));
    }
  }
  throw lastErr;
}

/**
 * Escape raw control characters that appear INSIDE JSON string literals.
 *
 * Models intermittently emit a literal newline inside a quoted string, which is
 * invalid JSON and makes JSON.parse throw "Bad control character in string
 * literal". Rejecting the whole batch over one stray \n wastes the call, so walk
 * the text tracking string state and escape them.
 */
function escapeControlCharsInStrings(json) {
  let out = '';
  let inString = false;
  let escaped = false;
  for (const ch of json) {
    if (escaped) { out += ch; escaped = false; continue; }
    if (ch === '\\') { out += ch; escaped = true; continue; }
    if (ch === '"') { inString = !inString; out += ch; continue; }
    if (inString) {
      if (ch === '\n') { out += '\\n'; continue; }
      if (ch === '\r') { out += '\\r'; continue; }
      if (ch === '\t') { out += '\\t'; continue; }
      if (ch < ' ') { continue; }          // drop other control chars outright
    }
    out += ch;
  }
  return out;
}

/** Tolerant JSON extraction — models wrap arrays in prose or fences. */
export function extractJsonArray(text) {
  const fenced = text.match(/```(?:json)?\s*([\s\S]*?)```/);
  const body = fenced ? fenced[1] : text;
  const start = body.indexOf('[');
  const end = body.lastIndexOf(']');
  if (start === -1 || end === -1) throw new Error(`No JSON array in response: ${text.slice(0, 200)}`);
  const slice = body.slice(start, end + 1);
  try {
    return JSON.parse(slice);
  } catch {
    return JSON.parse(escapeControlCharsInStrings(slice));   // let a second failure throw
  }
}
