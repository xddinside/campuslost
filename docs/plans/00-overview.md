# CampusLost evaluation delivery

Baseline: `2d0f8cb` on `main`. These are implementation briefs, not completed features. Every builder reads this overview, [shared-contracts.md](shared-contracts.md), then the assigned brief.

## The demo

Alice reports a lost bottle. Bob reports a similar found bottle with a photo and ownership question. Alice receives an explained suggestion and an in-app alert, submits a private answer, gets approved, and presents a pickup code to Bob. Bob verifies it; both reports resolve. Public browsing reveals no contacts, proof answers, or pickup codes.

## Work and merge order

1. One fixes agent implements [01-fixes.md](01-fixes.md) on `main`, including shared foundations, and returns a passing commit SHA.
2. From that exact SHA, start four agents together, each in its own worktree: `feature/matching` using [02-matching.md](02-matching.md), `feature/alerts` using [03-alerts.md](03-alerts.md), `feature/ownership` using [04-ownership.md](04-ownership.md), and `feature/handover` using [05-handover.md](05-handover.md).
3. Merge matching, ownership, handover, then alerts. Keep merge commits. Before each merge, the integrating lead merges current main into that branch and runs its checks. Each branch must also compile/test independently on fixed main. Do not rebase another agent's active work.

This planning task creates neither builders nor worktrees. Each future handoff supplies branch/SHA, files changed, exact checks/results, and remaining limits.

## Shared decisions and owners

The contract reference defines exact schemas, events, DTOs, API rules, component props, and cache keys. Approval reserves a FOUND item; only verified pickup completes its claim and resolves the linked reports. LOST-report responses remain a separate flow. Evidence review is manual. Matching uses public text/category/location/date; alerts consume committed match events. Query caches are scoped to identity.

- Fixes owns existing application files, runtime/config/dependencies, core models/services/repos/controllers/DTOs, `com.lostfound.contract`, `com.lostfound.workflow`, shared frontend types/query/auth, routes/Header/styles, seed data, and verification scripts. It wires empty feature entrypoints and optional adapters before the fork; they render nothing and never claim success.
- Matching owns new `com.lostfound.matching/**`, `frontend/src/features/matching/**`, and its tests.
- Alerts owns new `com.lostfound.notifications/**`, `frontend/src/features/notifications/**`, and its tests.
- Ownership owns new `com.lostfound.ownership/**`, `frontend/src/features/ownership/**`, and its tests.
- Handover owns new `com.lostfound.handover/**`, `frontend/src/features/handover/**`, and its tests.

Java packages live under `backend/src/main/java/`; tests use corresponding owned packages. Feature agents keep styles/types/helpers inside their directories. Propose shared-file changes to the lead instead of editing them independently. Generated routes and dependency files remain lead-owned.

Migrations follow merge order: fixes V1/V2, matching V10, ownership V20 if needed, handover V30, alerts V40. Keep Flyway out-of-order disabled and never edit applied migrations. The lead verifies the full sequence against fresh and existing schemas.

## Gates and scope

Use portless for every dev server. Each worktree gets distinct app names, ports, database, and photo directory through environment configuration. Wrap long commands in `timeout 180`; stop only task-owned processes by exact PID. Use only T3's built-in browser.

At the fixes gate and every merge: frozen dependency install, frontend build/typecheck, fresh backend clean verify on this box, isolated MariaDB API smoke checks, and the assigned race/privacy tests. A previous JAR is not build proof. Final rehearsal covers both accounts, unrelated-report ranking, private evidence, approval without resolution, one wrong OTP, correct pickup, both reports resolved, alert read state, refresh, and account switching.

Budget 3-4 hours for fixes/foundations, 3-5 hours of parallel work, and 2 hours for integration. Keep all four requested slices; defer AI/image embeddings, maps, QR tags, analytics expansion, email, SSO, and deployment. Defaults are local photos, deterministic matching, manual evidence review, six-digit OTPs with ten-minute expiry/five attempts, and in-app alerts.

No product decision blocks work. Nirvaan should identify a different demo machine/origin if applicable and may supply real campus locations/photos. Provision secrets privately outside Git.
