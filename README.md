# CampusLost — Lost & Found

A lost & found management system: report lost/found items, search, claim, approve
handover, and resolve.

- **Frontend:** TanStack Start (React 19, Vite 8), TanStack Query, Tailwind CSS v4 — `frontend/`
- **Backend:** Java 25, Spring Boot 4, Spring Security + JWT, Spring Data JPA, MariaDB, Flyway — `backend/`

## Prerequisites

- **Java 25** (the build is pinned to Eclipse Temurin 25.0.5+8; other 25.x builds work)
- **Node.js 22+** and **pnpm 12**
- **MariaDB 11+** running locally

Do not rely on the system JDK. Install the pinned build outside the repository:

```bash
scripts/bootstrap-java.sh          # verifies the SHA-256 and installs to ~/.local/share/campuslost
scripts/setup-db.sh                # creates lostfound + lostfound_test and the app user
scripts/bootstrap-local.sh         # writes .secrets/backend.properties and frontend/.env.local
```

`scripts/bootstrap-local.sh` generates the JWT and handover HMAC secrets into
`.secrets/backend.properties` (git-ignored, mode 600). The backend refuses to start
without a JWT secret. Re-run with `--force` to rotate secrets.

## Run

```bash
# Terminal 1 — API on http://127.0.0.1:8080 with demo data
scripts/dev-backend.sh

# Terminal 2 — frontend on http://localhost:3000 (https://campuslost.localhost via portless)
scripts/dev-frontend.sh
```

The frontend proxies `/api` to the backend. The demo seed only runs when the
`demo` profile is active **and** `app.demo.seed=true`; normal runs start empty.

## Demo accounts

Visible in the UI only when `VITE_DEMO_MODE=true` (written by `bootstrap-local.sh`).

| Role  | Email               | Password |
|-------|---------------------|----------|
| Admin | admin@lostfound.dev | admin123 |
| User  | alice@demo.dev      | demo123  |
| User  | bob@demo.dev        | demo123  |
| User  | carol@demo.dev      | demo123  |
| User  | dave@demo.dev       | demo123  |

## Tests and builds

```bash
# Backend: unit + integration tests against lostfound_test, then package
source scripts/java-env.sh
cd backend && ./mvnw clean verify

# Frontend: exact, audited dependencies
cd frontend
pnpm install --frozen-lockfile
pnpm typecheck
pnpm build
pnpm audit
```

The backend uses Flyway (`backend/src/main/resources/db/migration`). Existing
databases are adopted at baseline V1 and upgraded to the current schema on boot;
Hibernate runs with `ddl-auto: validate`. Tests use
`src/test/resources/application-test.yml` and the `lostfound_test` database.

## API overview

| Method | Path                             | Auth        | Description                                        |
|--------|----------------------------------|-------------|----------------------------------------------------|
| POST   | `/api/auth/register`             | —           | Create account                                     |
| POST   | `/api/auth/login`                | —           | JWT login (rate limited)                           |
| GET    | `/api/auth/me`                   | JWT         | Current user                                       |
| GET    | `/api/items`                     | —           | List/search (`q`, `type`, `category`, `location`, `status`) |
| GET    | `/api/items/{id}`                | —           | Item detail (public DTO)                           |
| GET    | `/api/items/{id}/edit`           | owner/admin | Editable snapshot with `version`                   |
| POST   | `/api/items`                     | JWT         | Report item                                        |
| PATCH  | `/api/items/{id}`                | owner/admin | Edit with optimistic `version`                     |
| PATCH  | `/api/items/{id}/status`         | owner/admin | Lifecycle transition                               |
| DELETE | `/api/items/{id}`                | owner/admin | Soft delete                                        |
| GET    | `/api/items/me/reported`         | JWT         | My reported items                                  |
| POST   | `/api/items/{id}/claims`         | JWT         | Claim an item                                      |
| GET    | `/api/items/{id}/claims`         | owner/admin | Claims on an item                                  |
| GET    | `/api/me/claims`                 | JWT         | My claims                                          |
| DELETE | `/api/claims/{id}`               | claimant    | Cancel a pending claim                             |
| PATCH  | `/api/claims/{id}`               | owner/admin | Approve / reject / complete                        |
| POST   | `/api/claims/{id}/release`       | awarded     | Release a reserved item                            |
| GET    | `/api/claims/{id}/contact`       | owner/awarded | Contact details after approval (`no-store`)      |
| POST   | `/api/photos`                    | JWT         | Upload photo (re-encoded JPEG/PNG/WebP)            |
| GET    | `/api/photos/{id}`               | —           | Serve photo                                        |
| GET    | `/api/admin/stats`               | ADMIN       | Dashboard stats                                    |
| GET    | `/api/admin/items`               | ADMIN       | All items                                          |
| GET    | `/api/admin/items/{id}/claims`   | ADMIN       | Claims for any item                                |
| POST   | `/api/admin/photos/cleanup`      | ADMIN       | Delete orphaned photos                             |

## Lifecycle

`OPEN → RESERVED → COMPLETED`, plus `CLOSED`/`CANCELLED` for withdrawn reports and
claims. Approving a claim reserves the item, rejects other pending claims, and makes
contact details available to the two parties. Handover completion or release moves
the item on. All transitions use row locks and return `409` on stale versions or
conflicting actions.

## Configuration

Everything is environment-driven (see `backend/src/main/resources/application.yml`):

| Variable | Default | Purpose |
|----------|---------|---------|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | local `lostfound` | Datasource |
| `JWT_SECRET` | none, required | Token signing |
| `APP_HANDOVER_HMAC_SECRET` | none | Handover tokens |
| `APP_PHOTOS_DIR` | `backend/uploads` | Photo storage (git-ignored) |
| `APP_CORS_ALLOWED_ORIGINS` | localhost + portless URL | Allowed browser origins |
| `SERVER_PORT` | `8080` | API port |
| `VITE_DEMO_MODE` | unset | Show demo credentials in the UI |

## Reset local data

```bash
sudo mariadb -e "DROP DATABASE lostfound; CREATE DATABASE lostfound CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
rm -rf backend/uploads/*
scripts/dev-backend.sh
```

Flyway recreates the schema and the demo profile reseeds items and claims.

## Feature branches

`frontend/src/features/{matching,notifications,ownership,handover}` expose typed
entrypoints and shared contracts (`frontend/src/lib/feature-contracts.ts`). The
lifecycle services they depend on (`ClaimWorkflow.completeVerified`,
`ClaimWorkflow.lockForHandover`) require an ambient transaction. Fork feature work
from the fixes milestone SHA and keep these shared paths stable.

## Notes

- Passwords are BCrypt-hashed; login and registration are rate limited in memory.
- API errors use a stable `{ code, message }` shape; 401/403 return JSON.
- Authenticated responses are `Cache-Control: private, no-store`.
- Uploads are decoded and re-encoded server-side; opaque random IDs, never user filenames.
