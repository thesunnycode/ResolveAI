# ResolveAI — frontend

React 19 + TypeScript + Vite + Tailwind v4 client for the ResolveAI backend.

## Run locally

```bash
npm install
npm run dev        # http://localhost:5176, proxies /api to http://localhost:8080
```

The backend must allow this origin: start it with `CORS_ALLOWED_ORIGINS=http://localhost:5176`.

Local seed logins (password `resolveai-local-2026`, workspace `acme`):
`admin@acme.com` (admin), `sana@acme.com` (team lead), `arjun@acme.com` (agent), `customer1@example.com` (customer).

## Scripts

- `npm run dev` — dev server
- `npm run build` — type-check and production build
- `npx tsc --noEmit -p tsconfig.app.json` — type-check only
