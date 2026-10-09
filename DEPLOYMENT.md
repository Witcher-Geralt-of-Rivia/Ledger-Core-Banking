# Deployment Guide

This project deploys as three managed pieces:

| Component | Platform | What it runs |
|-----------|----------|--------------|
| Database | **Neon** | Serverless PostgreSQL 16 |
| Backend API | **Render** | Spring Boot service (Docker) |
| Frontend | **Vercel** | React/Vite static site that calls the backend API directly |

The frontend takes the backend origin from the `VITE_API_BASE_URL` environment variable at
build time and calls the API cross-origin, so the backend must list the frontend's origin in
`CORS_ORIGINS`. No backend URL is committed to this repository.

---

## 1. Database — Neon

1. Create a project at https://neon.tech and a database named `ledgercore`.
2. From **Connection Details**, copy the host, role (user), and password.
3. Build the JDBC URL (note `sslmode=require`):
   ```
   jdbc:postgresql://<your-host>.neon.tech/ledgercore?sslmode=require
   ```
   Keep the username and password handy for Render.

> Single-role note: on Neon the app connects as the database owner, so the optional
> least-privilege `ledger_app` grants (migration `V3`) are skipped automatically. The
> append-only guarantee for the ledger and audit log is still enforced in the application
> layer (repositories expose insert + read only).

## 2. Backend — Render

The repository ships a Render Blueprint (`render.yaml`) and a `backend/Dockerfile`.

1. Push this repo to GitHub (already done).
2. In Render: **New → Blueprint**, select the repo. Render reads `render.yaml` and creates the
   `ledger-core-banking-api` web service.
3. Set the service environment variables (marked `sync: false`):
   - `DB_URL` → the Neon JDBC URL from step 1
   - `DB_USERNAME` → Neon role
   - `DB_PASSWORD` → Neon password
   - `CORS_ORIGINS` → your Vercel site origin, without a trailing slash (e.g.
     `https://your-frontend.vercel.app`). Required: the browser calls the API cross-origin.
   - `JWT_PRIVATE_KEY` → the RS256 signing key (see [JWT signing key](#jwt-signing-key)).
   - `SEED_ENABLED` → `true` for the first deploy to create demo data, then change to `false`
     and redeploy.
4. Deploy. Render builds the Docker image, Flyway runs the migrations on first boot, and the
   health check hits `/actuator/health`.
5. Copy the service URL, e.g. `https://your-backend.onrender.com`.

> Free plan: the service sleeps after inactivity, so the first request after idle can take
> ~30–60s (cold start). Neon free compute also auto-suspends similarly.

## 3. Frontend — Vercel

1. In Vercel: **Add New → Project**, import the repo, and set the **Root Directory** to
   `frontend`. The framework (Vite), build command, and output directory are picked up from
   `vercel.json`:

   | Setting | Value |
   |---------|-------|
   | Root Directory | `frontend` |
   | Framework Preset | Vite |
   | Install Command | `npm install` (Vercel's default for `package-lock.json`) |
   | Build Command | `npm run build` |
   | Output Directory | `dist` |

2. Add the environment variable `VITE_API_BASE_URL` (for both Production and Preview), set to
   the origin of your backend from step 2 with no trailing slash and no path, e.g.
   `https://your-backend.onrender.com`. It is compiled into the bundle at build time, so
   redeploy after changing it.
3. Deploy. Vercel serves the SPA. `vercel.json` sends client-side routes to `index.html` and
   deliberately leaves `/api/*` alone, so a missing `VITE_API_BASE_URL` shows up as failing
   API calls instead of being masked.
4. Set `CORS_ORIGINS` on Render to the Vercel site origin and redeploy the backend.
5. Open the Vercel URL and sign in.

`vercel.json` contains no backend URL. `VITE_*` values are public once built, so never put
credentials or other secrets in them. See [`frontend/.env.example`](frontend/.env.example).

If you change the Vercel domain later, update `CORS_ORIGINS` on Render to match.

---

## Local development

```bash
docker compose up --build      # full stack on :5173 (web), :8080 (api), :5432 (db)
```

See [`README.md`](README.md) for running services individually and for testing.

## Environment variable reference (backend)

| Variable | Required | Description |
|----------|----------|-------------|
| `DB_URL` | yes | JDBC URL to PostgreSQL (`sslmode=require` for Neon) |
| `DB_USERNAME` / `DB_PASSWORD` | yes | Database credentials |
| `PORT` | auto | Injected by Render; the app binds to it |
| `CORS_ORIGINS` | yes, for a hosted frontend | Allowed browser origin(s), comma-separated |
| `JWT_PRIVATE_KEY` | yes, in production | RS256 signing key: RSA private key, PKCS#8, base64 or PEM (see below) |
| `SEED_ENABLED` | no | `true` seeds a demo admin + customer once (default `false`) |

### JWT signing key

Access tokens are signed with RS256. Set `JWT_PRIVATE_KEY` to an RSA private key of at least
2048 bits in PKCS#8 form, either as single-line base64 of the DER encoding or as PEM
(`-----BEGIN PRIVATE KEY-----`). The public key is derived from it, so this is the only value
to configure. Generate one with:

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 \
  | openssl pkcs8 -topk8 -nocrypt -outform DER | base64 -w0
```

The `pkcs8 -topk8` step is required. On its own, `openssl genpkey -outform DER` writes the
older PKCS#1 encoding, which the application rejects at startup.

Keep the key in the host's secret store or a protected environment file, never in the
repository. With the key set, a restart does not invalidate access tokens and several
instances can share it. Replacing the key invalidates every outstanding access token; refresh
tokens are unaffected, so clients obtain a new access token transparently.

If `JWT_PRIVATE_KEY` is empty, a key is generated at startup and a warning is logged. That is
meant for local development and tests only. A value that is set but not a valid key stops the
application at startup rather than falling back to a generated key.

## Environment variable reference (frontend)

| Variable | Required | Description |
|----------|----------|-------------|
| `VITE_API_BASE_URL` | on Vercel | Backend origin, e.g. `https://your-backend.onrender.com`. Leave unset behind a same-origin `/api` reverse proxy (Vite dev server, Docker/nginx) |

## Maintainer & contact

**Current Maintainer:** Jakub Kowalski ([GitHub](https://github.com/Witcher-Geralt-of-Rivia) ·
[Portfolio](https://jakub-kowalski-portfolio.vercel.app))

**Repository:** https://github.com/Witcher-Geralt-of-Rivia/Ledger-Core-Banking

For project questions, maintenance requests, or bug reports, use the GitHub repository/issues or
the portfolio contact channels.
