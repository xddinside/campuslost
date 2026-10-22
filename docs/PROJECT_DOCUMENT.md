# CampusLost — Project Document

**A campus lost-and-found platform with explainable matching, private ownership questions, automatic match alerts, and OTP-verified handover.**

---

## 1. Executive summary

CampusLost is a working campus lost-and-found web application. It replaces notice boards, group chats, and the security desk with one searchable inventory where LOST and FOUND reports are two halves of one event.

Matching proposes up to five opposite-type reports with a similarity score and plain reasons; strong pairs create in-app alerts. A finder may attach a private ownership question, answered privately and reviewed before any decision. Approval reserves an item and unlocks contacts but does not claim collection. The claimant generates a short-lived code on their device, the finder enters it on theirs, and only that verified exchange resolves both reports.

The governing distinction: **a lead is not proof, and approval is not pickup**. A private answer is evidence; only a two-device code records a return.

Stack: React 19 / TanStack Start, a stateless Java 25 / Spring Boot API, MariaDB, Flyway, and JWT; matching is local and needs no external AI service.

---

## 2. The problem

A lost item creates two pieces of information: the owner knows what was lost and roughly where; the finder knows what was found and roughly when. Neither sees the other's report. On a campus, thousands share the same libraries, halls, gyms, and labs, and a finder has no way to advertise beyond a group chat buried by evening.

Four failures recur.

- **Fragmentation.** Reports live on boards, in chats, email, and at a security desk. There is no single inventory, and recall depends on checking every channel.
- **Vocabulary mismatch.** An owner writes “Hydro Flask”; a finder writes “dark blue water bottle”. Keyword search fails when descriptions diverge.
- **The verification gap.** A public description must attract the owner without teaching an impostor what to say. “Describe it to prove it is yours” defeats itself, while demanding serial numbers or ID documents creates a privacy problem.
- **The handover gap.** “Approved” is not “collected”. An approved claim can be abandoned, and resolving at approval removes the item from search before it is returned.

The data is sensitive: a report can reveal a phone, wallet, keys, medication, or ID card. Privacy must live in fields and endpoints, not a settings page. The campus helps — most users have a college account, handovers are in person, and an administrator can intervene — and CampusLost uses that trust without exposing the data.

---

## 3. Objectives

| # | Objective | How it is met |
|---|---|---|
| O1 | One inventory | A single `items` model and one public browse/search API. |
| O2 | Proactive discovery | Five explained suggestions; strong pairs create alerts. |
| O3 | Explained suggestions | A 0–100 similarity score with positive reasons, never an ownership probability. |
| O4 | Privacy-preserving proof | One private question per found report, readable only by authorized parties. |
| O5 | Verified handover | Approval reserves; a six-digit finder-entered code resolves both reports. |
| O6 | Concurrency correctness | Ascending locks, a locked claim context, versions, unique reservations. |
| O7 | Realistic operation | Local matching, local photos, one database, stateless API. |

A secondary goal: **honest language** — “similarity score”, not “match probability”; “approved, awaiting pickup”, not “returned”.

---

## 4. The solution

CampusLost has two report types. **LOST** means “I lost something”, with when and where. **FOUND** means “I found something”, with when, where, and a private question only a genuine owner should answer. A signed-in user browses and claims; the reporter reviews. For a found item, approval reserves it and unlocks contacts; the claimant generates a code, the finder enters it, and both reports resolve.

| Link | Artefact | Establishes |
|---|---|---|
| Discovery | Score and reasons | Why a report is worth opening |
| Proof | Question and answer snapshot | The claimant knows a withheld detail |
| Decision | Decision with actor and time | A human judged the evidence |
| Handover | Keyed, expiring, attempt-limited code | Two devices confirmed the exchange |
| Resolution | Completed claim, equal timestamps | The item left the open inventory |

The principles: a lead is not proof; private data stays private by default; the server enforces transitions; missing policies or keys fail closed; mechanisms stay deterministic and auditable.

---

## 5. User roles

| Role | Obtained by | Capabilities |
|---|---|---|
| Visitor | No account | Browse, search, open reports. No claims or private data. |
| Registered user | Self-registration | Report and manage own items, claim or respond, receive alerts, answer questions. |
| Reporter / finder | Creating a report | Edit, attach photos, set the question, review claims, approve, reject, release, resolve, close, delete. |
| Claimant | Claiming a FOUND report or answering a LOST one | Track, cancel while pending, view own evidence, see contacts, generate a code. |
| Administrator | `ADMIN` role | Counts, all reports, claims, authorized evidence and contacts, verify or release handovers, delete, cleanup. |

The server enforces three boundaries: a reporter cannot claim their own report; a finder cannot claim a found item for someone else; and a claimant cannot verify their own pickup.

---

## 6. The full journey

1. **Browse.** OPEN reports show by default, with filters in the URL. A signed-out user who tries to claim is sent to login and returned.
2. **Register or log in.** Passwords are BCrypt-hashed; login returns a JWT and profile, validated against `/api/auth/me` before private queries.
3. **Post.** The form collects type, title, description, category, location, date, photo, and — for FOUND — the private question. Type is immutable.
4. **Match and alert.** After commit, matching proposes up to five candidates and alerts both authors when a pair is strong. A suggestion preselects the claimant's lost report.
5. **Claim.** On a FOUND report the claimant writes a message and answers the question. On a LOST report the response is “I found this”.
6. **Review.** The reporter sees the claimant, message, linked report, and private answer, then approves or rejects manually.
7. **Approve.** Both reports become RESERVED; competing claims are rejected; `resolvedAt` stays empty; contacts unlock. The UI says “Approved, awaiting pickup”.
8. **Pick up.** The claimant generates a six-digit code; the finder enters it on their own device. Success completes the claim, resolves both with one timestamp, and records a verified handover.
9. **After.** Resolved reports leave browsing but stay in history and cannot reopen after verified handover. If pickup fails, release cancels the claim, reopens both, and hides contacts.

---

## 7. Features

**Reports and search.** Reports carry type, title, description, category, location, date, status, photo, and reporter. The server caps lengths (120/4,000/60/120), rejects dates before 2000 or beyond tomorrow, and rejects stale edits with `409 STALE_VERSION`. Search matches title, description, or location; filters are enum-validated and default to OPEN. Photos are JPEG, PNG, or WebP up to 5 MiB and 12,000 pixels per side, re-encoded to strip metadata.

**Claims.** A claim records who asked, what they said, what was decided, and when. Creating one requires a visible OPEN target, a claimant other than the reporter, and no active claim; a FOUND claim may link the claimant's own OPEN LOST report. Approval and rejection belong to the reporter or an administrator; rejected and cancelled history remains.

**Explainable matching.** A report's author sees up to five opposite-type candidates with a 0–100 score and only positive reasons. Same-reporter, same-type, non-OPEN, and deleted pairs are excluded, and a text gate must be cleared before category, location, or date can contribute. Results are canonical LOST/FOUND snapshots with both source versions.

**Match alerts.** A qualifying pair produces one in-app notification per author. Matching publishes an immutable `MatchCandidatesChangedEvent`; notifications consume it after commit and upsert one row per recipient with the key `match:<lostId>:<foundId>`. Replays, concurrent delivery, and restarts do not duplicate alerts or reset read state. Ineligible pairs deactivate rather than delete, and eligibility is rechecked at read time. The bell polls every 20 seconds while visible, refreshes on focus, and clears on account switch.

**Private questions.** One question per FOUND report, never in the public DTO. New FOUND reports require 10–200 characters; LOST reports cannot carry one; older reports show “No question supplied”. The claimant fetches the prompt through an authenticated endpoint. The claim stores an exact prompt snapshot with the answer under the item lock, so later edits do not change evidence; edits freeze while claims are pending. Reads are limited to the claimant, reporter, and administrators, and evidence is excluded from DTOs, matching, events, notifications, and logs.

**OTP handover.** The claimant generates a six-digit code shown only on their device; the finder enters it on theirs, and there is no bypass, including for administrators. A challenge row holds a keyed HMAC digest bound to claim and generation, expiry, failed attempts, next issue time, and verification time. Codes last ten minutes, allow five failures, and regenerate after a 60-second cooldown, invalidating the old digest. Verification locks the claim then the challenge, compares in constant time, and completes in one transaction. Wrong or expired is a 400 with attempts remaining; exhaustion is a 429; wrong lifecycle is a 409, and failure cannot half-resolve the pair. Status returns `NOT_ISSUED`, `READY`, `EXPIRED`, `LOCKED`, or `COMPLETED`, never the code.

---

## 8. What makes CampusLost novel

“Novel” means a distinctive evaluation contribution, not a claim that no similar component exists anywhere.

- **An integrated trust chain.** Most campus tools stop at a listing or a claim button. CampusLost joins discovery, explanation, private proof, human review, and handover into one inspectable lifecycle.
- **Explainability without a model.** The matcher publishes its weights, gate, thresholds, and reasons, so an evaluator can test the ranking with fixtures — no key, GPU, model, or network call.
- **Privacy as a protocol.** Questions are excluded from DTOs, matching, events, notifications, and logs. Answers are claim snapshots. Contacts unlock only after approval and disappear on release. Only a keyed code digest is stored.
- **Correct lifecycle semantics.** Approval reserves, verified pickup resolves, release reopens, and manual resolution lacks a badge — preventing “approved means returned” and “resolved means verified”.

---

## 9. System architecture

```
┌──────────────────────────── Browser ────────────────────────────┐
│ React 19 + TanStack Start (SSR) + Router + Query               │
│ Browse · report · detail · activity · admin · match panel      │
│ alert bell · ownership fields · handover panel                 │
└───────────────────────────────┬─────────────────────────────────┘
                                │ HTTPS · JSON · Bearer JWT
┌───────────────────────────────▼─────────────────────────────────┐
│ Spring Boot 4.1 application (stateless)                        │
│ JWT auth → rate limit → private-response filter                │
│ Controllers → Services → ClaimWorkflow → JPA repositories      │
│ Modules: matching · notifications · ownership · handover       │
└───────────────┬─────────────────────────────────┬───────────────┘
                ▼                                 ▼
        MariaDB 11 (InnoDB)              Local photo directory
        Flyway schema                    (outside Git)
```

**Frontend.** File-based routes cover browse, report, detail, edit, login, register, activity, and admin. TanStack Router validates search parameters, including `lostReportId` from a match. TanStack Query separates public from account-scoped keys. A stored token is only a hint: `/api/auth/me` runs first, the cache clears on identity change, and SSR uses one client per request.

**Backend.** Layers are security, controllers, services, `ClaimWorkflow`, repositories, the domain model, features, and contracts. `ClaimWorkflow` is the single place where transitions occur: it locks, checks permissions and state, transitions, flushes, and publishes events. No session state is kept.

**Events.** A report write publishes `ItemChangedEvent`. After commit, matching recomputes affected pairs, replaces snapshots, and publishes `MatchCandidatesChangedEvent`; notifications then upsert or deactivate alerts. A matching failure cannot roll back a report, and a notification failure cannot roll back a match. Matching also catches up on startup.

**Deployment.** One frontend proxying `/api`, one stateless API, one MariaDB database plus a test database, and one local photo directory. Secrets are git-ignored with mode 600, CORS is origin-restricted, and the topology is single-node.

---

## 10. Technology stack

| Layer | Technology | Why |
|---|---|---|
| Language | Java 25 (pinned Temurin 25.0.5+8) | Modern LTS, reproducible builds. |
| Backend | Spring Boot 4.1, Spring Security | Mature web, validation, security, JPA. |
| Auth | JJWT 0.12.6, BCrypt | Explicit JWT keys; salted hashing. |
| Persistence | Hibernate 7.4.5, MariaDB 11, Flyway | Locks, versions, generated columns, migrations. |
| Images | TwelveMonkeys ImageIO 3.15.3 | WebP decoding. |
| Tests | JUnit 5, Spring Boot Test, MockMvc, AssertJ | Real-database integration, HTTP contracts. |
| Build | Maven Wrapper, pnpm 12, Node 22+ | Reproducible, lockfile-based builds. |
| Frontend | React 19.2, TanStack Start 1.169, Router 1.170, Query 5.104 | SSR, typed routes, caching. |
| UI | Vite 8.3, TypeScript 6.0, Tailwind 4.3, Base UI 1.6, Phosphor 2.1 | Fast builds, typed, accessible UI. |

No external LLM, broker, server session, native app, or cloud storage: each adds cost, latency, or privacy surface.

---

## 11. Data model

| Table | Purpose and key fields |
|---|---|
| `users` | Unique email, BCrypt hash, nullable phone, `USER`/`ADMIN`. |
| `items` | Immutable `LOST`/`FOUND`, title, description, category, location, date, `OPEN`/`RESERVED`/`RESOLVED`/`CLOSED`, reporter, timestamps, `resolved_at`, `deleted_at`, photo, private question, version. |
| `claims` | Target, optional linked lost report, claimant, message, `PENDING`/`APPROVED`/`REJECTED`/`CANCELLED`/`COMPLETED`, prompt snapshot, answer, decision/completion/release fields, `handover_verified`, version. |
| `photos` | Opaque UUID, uploader, random storage name, type, size, dimensions, attached item. |
| `match_candidates` | Unique lost/found pair, score, reasons, source versions. |
| `notifications` | Recipient, type, unique `(recipient_id, dedupe_key)`, references, versions, score, reasons, active, read state. |
| `handover_challenges` | Unique claim, keyed digest, expiry, failed attempts, next issue time, verified time, generation. |

Two generated `claims` columns equal their source IDs only for APPROVED or COMPLETED claims and are unique, so two active reservations on one report — or one linked lost report — are impossible. Reports are soft-deleted; photos are deleted when detached or replaced, with cleanup for orphans older than 24 hours.

---

## 12. Key API endpoints

Paths are under `/api`; bodies are JSON except photos; errors use `{code, message, fieldErrors?}`.

| Area | Endpoints |
|---|---|
| Auth | `POST /auth/register`, `POST /auth/login`, `GET /auth/me` |
| Reports | `GET /items`, `GET /items/{id}`, `GET /items/{id}/edit`, `POST /items`, `PATCH /items/{id}`, `PATCH /items/{id}/status`, `DELETE /items/{id}`, `GET /items/me/reported`, `GET /items/{id}/matches`, `GET /items/{id}/ownership-question` |
| Claims | `POST /items/{id}/claims`, `GET /items/{id}/claims`, `GET /me/claims`, `DELETE /claims/{id}`, `PATCH /claims/{id}`, `POST /claims/{id}/release`, `GET /claims/{id}/contact`, `GET /claims/{id}/evidence` |
| Handover | `POST /claims/{id}/handover/code`, `GET /claims/{id}/handover`, `POST /claims/{id}/handover/verify`, `POST /claims/{id}/handover/release` |
| Notifications | `GET /me/notifications?limit=20&beforeId=...`, `PATCH /me/notifications/{id}/read`, `POST /me/notifications/read-all` |
| Photos and admin | `POST /photos`, `GET /photos/{id}`, `GET /admin/stats`, `GET /admin/items`, `GET /admin/items/{id}/claims`, `POST /admin/photos/cleanup` |

Standard codes apply (400 validation, 401 credentials, 403 role, 404 missing, 409 conflict, 429 limit, 503 fail-closed); private responses carry `Cache-Control: no-store, private`.

---

## 13. Matching logic

Matching answers one question: which opposite-type report most likely describes the same physical item? It returns a ranked, explained shortlist, never an ownership decision. The score is a **similarity score** — not a probability and not evidence of ownership.

It uses only public fields: title, description, category, location, date, status, and deletion state. It excludes questions and answers, contacts, codes, photo metadata, claimant identity, and claim messages.

Text is normalized, stripped of stop words, tokenized, and compared with a cosine-style overlap where title tokens weigh more. Metadata is then combined:

```
score = round(100 × (
    0.60 × textSimilarity
  + 0.20 × categoryMatch
  + 0.15 × locationSimilarity
  + 0.05 × dateProximity
))

dateProximity = max(0, 1 − daysApart / 14)
```

Eligibility requires one LOST and one FOUND report, different reporters, both OPEN and not deleted, and text similarity of at least **0.20**. The gate matters: metadata supports a textual lead but cannot create one.

| Threshold | Effect |
|---|---|
| Text similarity ≥ 0.20 | Eligible for scoring |
| Score ≥ 35 | Appears in the match panel (top five) |
| Score ≥ 60 | Also produces an alert |

Ties break by score then candidate ID. Reasons appear only for positive signals — “Similar item description”, “Same category”, “Same campus location”, “Reported two days apart”. Thresholds are heuristics, not calibrated confidence.

For illustration: text 0.52, exact category, location 1.00, and two days apart give `0.60×0.52 + 0.20 + 0.15 + 0.05×0.857 = 0.7049`, rounded to **70** — above the alert threshold. At 0.18 the pair is ineligible regardless of metadata.

Snapshots are keyed by the canonical pair, replaced rather than patched, and invalidated when either side becomes ineligible. Stale versions are discarded, failures do not roll back reports, and startup catch-up republishes current snapshots. Matching uses no LLM, embeddings, OCR, or visual similarity and does not claim calibration.

---

## 14. Security and privacy

**Authentication.** Registration and login are the only public credential endpoints. Passwords are BCrypt-hashed; login issues a JWT signed with a 256-bit HMAC key, valid 24 hours, and every request reloads the user.

**Authorization.** Public reports and photos are open; editing, status, deletion, review, and release belong to the reporter or an administrator; others may claim; claimants may cancel while pending; contacts and evidence are limited to claimant, reporter, and administrators; codes are generated by the claimant and verified by the reporter or an administrator. Private routes are declared before public GET rules.

**Minimization.** The public item DTO contains ID, type, title, description, category, location, date, status, reporter ID and name, timestamps, version, photo URL, and pending-claim count. It never contains email or phone, the question or answer, claimant identity, messages, codes, or storage names.

**Caching.** Private responses are `no-store, private`; contacts and code issuance are non-cacheable. Public photos use a one-hour cache with `nosniff`. Account-scoped query keys and per-request SSR clients prevent cross-account leakage.

**Abuse and uploads.** Login allows 10 attempts per 60 seconds and registration 5 per 300 seconds, returning 429 with `Retry-After`. Photos require authentication, are JPEG, PNG, or WebP up to 5 MiB and 12,000 pixels per side, are re-encoded to strip metadata, get random names, attach only to their uploader's report, are served only when attached to a visible report, and are checked against the storage root.

**Codes.** Six random digits, ten-minute expiry, five failures then LOCKED, 60-second cooldown, keyed HMAC digest bound to claim and generation, constant-time comparison, plaintext only in issuance, no logging or URL exposure. Regeneration invalidates the prior digest.

**Integrity.** Mutations lock items ascending then the claim, re-read under lock, and run at READ COMMITTED. Optimistic versions reject stale edits. Unique generated columns enforce one active reservation per report and linked lost report. Failed attempts commit, while failed completion cannot half-resolve the pair.

**Other controls.** CORS is origin-restricted; CSRF is disabled because the JWT travels in a header; JPA binding prevents SQL injection; errors expose no stack traces; the demo seed runs only on an empty database.

---

## 15. Item and claim lifecycle

```
Item:   OPEN ──approve FOUND claim──▶ RESERVED ──verified pickup──▶ RESOLVED
         │  ▲                            │                            │
         │  └────── release ─────────────┘                            │
         ├──────── manual resolve ────────────────────────────────────┘
         │            └── reopen (only without verified handover) ────┘
         └──────── close ──▶ CLOSED (terminal)

Claim:  PENDING ──approve FOUND──▶ APPROVED ──verified pickup──▶ COMPLETED
           ├── reject ──▶ REJECTED
           ├── cancel ──▶ CANCELLED
           ├── approve LOST response ──▶ COMPLETED (no verified badge)
           └── APPROVED ──release──▶ CANCELLED
```

`RESERVED` cannot be set through the status endpoint; only approval creates it, blocking edits, status changes, and deletion until release. Approval clears `resolvedAt` on both reports and unlocks contacts. Verified pickup sets both RESOLVED with one timestamp and marks the claim COMPLETED with `handover_verified = true`. A linked lost report must be LOST, OPEN, and owned by the claimant, and participates in at most one approved claim. Release cancels, reopens both, records the reason, and denies contacts.

A LOST response is separate: “I found this”, no question or code, completed on approval without a badge. Legacy approved claims on resolved items migrate to COMPLETED but remain unverified. Every transition reads IDs, locks items ascending, locks the claim, re-reads, checks actor and preconditions, applies, flushes, and publishes.

---

## 16. Testing and verification

The backend suite runs against a real MariaDB test database with a clean Flyway schema, because the lifecycle depends on real locks and constraints. Lifecycle tests cover FOUND approval reserving without resolving, LOST approval completing without a badge, release reopening and blocking contacts, one reservation per linked lost report, simultaneous approvals producing one winner, fail-closed evidence, duplicate and reserved-state rejections, soft deletion, stranger access, and stale versions. HTTP and privacy tests cover private routes returning 401 before public rules, public JSON without email, phone, question, or answer, malformed enums, authorized readers, participant-only contacts, photo and traversal rules, expired tokens, and the shared error shape.

Feature scenarios cover matching (weak and strong pairs, negatives, ranking, exclusion rules, refresh), alerts (one per author, replay, read state, isolation), ownership (no leakage, authorized prompt fetch, atomic validation, frozen edits, snapshots), and handover (all code states, cooldown, invalidation, unauthorized roles, simultaneous attempts, no half-resolved state). The frontend uses `pnpm typecheck` and `pnpm build`, preserves input after failure, and checks labels, keyboard access, focus return, and account-switch cache clearing; builds use `pnpm install --frozen-lockfile`, `pnpm audit`, and `./mvnw clean verify`. No usage statistics or benchmark scores are claimed.

---

## 17. Limitations and future scope

**Limitations.** Matching is text-first and deterministic: it handles vocabulary overlap but not images, OCR, serial numbers, or deep paraphrase, and short descriptions can fail the gate. Evidence review is manual, and one question per FOUND report is supported. Alerts are in-app only. The application assumes one campus and one instance; locations are free text, rate limiting is in memory, and photos are local. There is no institutional identity, mobile app, QR, map, or pickup scheduling, and administration lacks analytics and an audit console.

**Future scope.** Add image embeddings or OCR as extra explainable signals; integrate campus SSO; add email, SMS, or web push; offer QR-assisted pickup with the same digest and two-device rules; replace free-text locations with a campus map; support multi-campus tenancy; add flagging, moderation, and an audit trail; move photos and events to shared infrastructure; instrument privacy-preserving metrics; and complete a WCAG audit with localization.

---

## 18. Conclusion

Lost property is a trust problem, not just a listing problem. CampusLost gives a campus one searchable inventory, helps owners and finders discover each other with understandable suggestions, protects the proof of ownership, keeps a human in the decision, and confirms the handover before declaring success.

The capabilities fit together: matching turns two reports into a reasoned lead, alerts deliver it, private questions test knowledge without publishing it, and OTP verification binds the record to a real exchange. The foundation is a stateless API, JWT authorization, re-encoded photos, soft deletion, optimistic versions, pessimistic locks, unique reservations, and versioned migrations.

It never claims a match is ownership, approval is pickup, or a legacy completion is verified. It shows what it knows, protects what it should not reveal, and records what actually happened.

---

## Appendix — Demo accounts, commands, and talking points

The demo seed runs only under the explicit demo profile on an empty database. Credentials appear only when `VITE_DEMO_MODE=true`.

| Role | Email | Password |
|---|---|---|
| Admin | `admin@lostfound.dev` | `admin123` |
| User | `alice@demo.dev` | `demo123` |
| User | `bob@demo.dev` | `demo123` |
| User | `carol@demo.dev` | `demo123` |
| User | `dave@demo.dev` | `demo123` |

```bash
scripts/bootstrap-java.sh     # pinned JDK
scripts/setup-db.sh           # lostfound + lostfound_test
scripts/bootstrap-local.sh    # secrets and demo settings
scripts/dev-backend.sh        # API :8080 with demo data
scripts/dev-frontend.sh       # UI :3000

source scripts/java-env.sh && cd backend && ./mvnw clean verify
cd frontend && pnpm install --frozen-lockfile && pnpm typecheck && pnpm build && pnpm audit
```

`JWT_SECRET` and `APP_HANDOVER_HMAC_SECRET` are required; photo directory, CORS origins, port, and rate limits have documented defaults.

> CampusLost does not guess who owns an item. It explains why two reports might be the same, lets a human review private evidence, and requires a two-device code before it records that the item was returned.
