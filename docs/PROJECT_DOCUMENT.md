# CampusLost — Project Document

**A campus lost-and-found platform with explainable matching, private ownership questions, automatic match alerts, and OTP-verified handover.**

Prepared for the college IP evaluation and presentation team.

---

## Contents

1. [Executive summary](#1-executive-summary)
2. [The problem](#2-the-problem)
3. [Objectives](#3-objectives)
4. [The solution](#4-the-solution)
5. [User roles](#5-user-roles)
6. [The full user journey](#6-the-full-user-journey)
7. [Features in detail](#7-features-in-detail)
8. [What makes CampusLost novel](#8-what-makes-campuslost-novel)
9. [System architecture](#9-system-architecture)
10. [Technology stack and reasons](#10-technology-stack-and-reasons)
11. [Data model](#11-data-model)
12. [Key API endpoints](#12-key-api-endpoints)
13. [How matching scores and explains](#13-how-matching-scores-and-explains)
14. [Security and privacy design](#14-security-and-privacy-design)
15. [Item and claim lifecycle](#15-item-and-claim-lifecycle)
16. [Testing and verification](#16-testing-and-verification)
17. [Limitations and future scope](#17-limitations-and-future-scope)
18. [Conclusion](#18-conclusion)
19. [Appendix A — Glossary](#appendix-a--glossary)
20. [Appendix B — Demo accounts and commands](#appendix-b--demo-accounts-and-commands)

---

## 1. Executive summary

CampusLost is a working web application for reporting, finding, claiming, and returning lost property on a college campus. It replaces the scattered notice board, group chat, and security-desk workflow with one searchable system that treats a lost item and a found item as two halves of the same event.

The application does more than list reports. When a report is posted, CampusLost compares it with opposite-type reports and presents up to five suggestions, each with a similarity score and plain-language reasons such as “Similar item description”, “Same campus location”, and “Reported two days apart”. Strong matches produce in-app alerts, so an owner does not have to keep refreshing a search page. A found-item reporter can attach a private ownership question to the report; a claimant answers it privately, and the reporter reviews that answer before deciding. Approval reserves the item but does not pretend the item has been collected. The claimant generates a short-lived pickup code on their own device, the finder enters it on theirs, and only that verified exchange resolves both reports.

The design is built around one distinction: **a lead is not proof, and approval is not pickup**. Matching helps people find each other; it never decides ownership. A private question gives the finder a signal; it does not create an automatic “verified owner” badge. A pickup code confirms that two people met and completed the handover; only then does the system mark the reports resolved.

CampusLost is a full-stack system: a React 19 / TanStack Start frontend, a stateless Java 25 / Spring Boot backend, MariaDB with versioned Flyway migrations, JWT authentication, server-side image processing, and a lifecycle engine that uses database locks and optimistic versions to keep competing claims correct. The interesting contribution is not a single algorithm. It is the composition: explainable discovery, privacy-preserving evidence, human review, and cryptographic handover joined into one auditable lifecycle.

### At a glance

| Question | Answer |
|---|---|
| What is it? | A campus lost-and-found web application with matching, alerts, private proof, and verified handover. |
| Who uses it? | Visitors, registered students and staff, and administrators. |
| What problem does it solve? | Fragmented reporting, vocabulary mismatch, unsafe public proof, and unverified handovers. |
| What is the core idea? | Match reports automatically, explain why, prove ownership privately, and confirm collection with a two-device code. |
| What is technically distinctive? | A deterministic, explainable matcher; event-driven match alerts; ownership evidence with snapshot semantics; HMAC-protected, attempt-limited pickup codes inside a locked claim workflow. |
| What does it run on? | React 19 + TanStack Start, Java 25 + Spring Boot 4, MariaDB 11, Flyway, JWT, local photo storage. |
| Does it need an external AI service? | No. Matching is local and deterministic, with no model download, API key, or network dependency. |

---

## 2. The problem

### 2.1 Lost property is a matching problem under uncertainty

A lost item creates two separate pieces of information: the owner knows what was lost and roughly where; the finder knows what was found and roughly when. Neither side sees the other's report. The job of a lost-and-found system is to bring those two halves together without exposing sensitive details and without letting the wrong person collect the item.

On a campus this problem is unusually concentrated. Thousands of people share the same libraries, lecture halls, gyms, cafeterias, labs, and transit stops. Items are left behind in predictable places and found by people who have no reason to know the owner. Turnover is high, locations are reused every hour, and the person who finds an item usually has no convenient way to advertise it beyond a group chat that will be buried by evening.

### 2.2 Why existing campus practice fails

Most campuses rely on a combination of physical notice boards, messaging groups, email lists, and a security desk. That combination has four recurring failures.

**Fragmentation and recall.** Reports live in different places. An owner has to remember to check each one, and a finder has to guess where an owner would look. A notice on a board is invisible to someone who only reads a chat; a chat message is invisible to someone who only visits the desk. There is no single inventory and no reliable way to search it.

**Vocabulary mismatch.** People describe the same object differently. An owner writes “Hydro Flask”; a finder writes “dark blue water bottle”. An owner writes “MacBook Pro”; a finder writes “laptop in a blue case”. Exact keyword search fails precisely when help is needed, because the two sides have different knowledge and different vocabularies.

**The verification gap.** A public description has to be informative enough to attract the owner, but not so informative that anyone can repeat it back and claim the item. Asking “describe it to prove it is yours” in public defeats itself. Asking for a serial number, a passcode, or an identity document creates a new privacy and security problem. Campuses need a way to test a claim without publishing the test.

**The handover gap.** Even when the right two people find each other, “approved” is not “collected”. A claim can be approved and then abandoned. The owner may not show up, the finder may lose interest, or the item may be handed over informally with no record. A system that marks an item resolved at approval is lying about the physical world, and it removes the item from search before it has actually been returned.

### 2.3 The privacy tension

Lost-and-found data is unusually sensitive for its size. A found-item report can reveal that a named person lost a phone, a wallet, keys, medication, or an identity card. Contact details are valuable to stalkers and scammers. A public claim message can reveal an address, a schedule, or a distinguishing feature that should stay private. A pickup code is a bearer secret: anyone who sees it can collect the item.

The design therefore cannot treat privacy as a later settings page. It has to decide, field by field, what is public, what is visible to a signed-in claimant, what is visible only to the reporter and administrators, and what is never stored in plaintext at all.

### 2.4 Why it matters on a campus

Recovering a belonging is not a trivial convenience. Keys and identity cards affect access to buildings and services. Phones and laptops contain coursework, accounts, and personal data. A lost medication bag or wallet can create immediate risk. The cost of a failed handover is paid twice: the owner loses the item, and the finder's willingness to help is wasted.

A campus also has a trust advantage that a public marketplace does not. Most users are reachable through a college account, most handovers happen in person on campus, and there is an administrator who can intervene. CampusLost is designed to use that advantage without exposing the personal data that makes it valuable.

---

## 3. Objectives

The project set out to meet seven design objectives. Each is stated as an outcome rather than a vanity metric.

| # | Objective | How CampusLost meets it |
|---|---|---|
| O1 | **One canonical inventory.** Every lost and found report should live in one searchable place with consistent metadata. | A single `items` model with type, title, description, category, location, date, status, photo, and reporter. Public browse and search read from one API. |
| O2 | **Proactive discovery.** An owner should not have to guess the right keywords or keep checking manually. | Explainable matching proposes up to five opposite-type reports after a report is posted. Strong matches create in-app alerts. |
| O3 | **Explained suggestions.** A user should understand why a report was suggested before acting on it. | Every suggestion carries a 0–100 similarity score and only the reasons that are actually positive. The score is explicitly a similarity signal, not an ownership probability. |
| O4 | **Privacy-preserving proof.** A claimant should prove knowledge without publishing the proof or exposing contact details. | One private ownership question per found report. The prompt is visible only to signed-in users through an authorized endpoint; the answer is visible only to the claimant, the target reporter, and administrators. |
| O5 | **Verified handover.** Approval should reserve; only a real pickup should resolve. | A six-digit pickup code, generated by the claimant and entered by the finder, completes the handover and resolves both linked reports atomically. |
| O6 | **Correctness under concurrency.** Two people acting at once must not both win, and stale screens must not overwrite newer state. | Row locks in ascending item-ID order, a locked claim context, optimistic `version` checks, and database uniqueness constraints on active reservations. |
| O7 | **Realistic operation.** The system should run on ordinary campus infrastructure without a paid AI service or a complex deployment. | Deterministic local matching, local photo storage, a single MariaDB database, environment-driven configuration, and a stateless API. |

A secondary goal runs through all seven: **honest language**. The interface says “similarity score”, not “match probability”. It says “approved, awaiting pickup”, not “returned”. It shows a verified badge only when a verified handover record exists. It tells users when contact details unlock and when they disappear again.

---

## 4. The solution

### 4.1 What CampusLost is

CampusLost is a responsive web application with two report types:

- **LOST** — “I lost something.” The reporter describes the item and when and where it was lost.
- **FOUND** — “I found something.” The reporter describes the item, where and when it was found, and a private question that a genuine owner should be able to answer.

A signed-in user can browse public reports, search and filter them, open a report, and send a claim or response. The reporter of the target report reviews claims. For a found item, approval reserves the item for one claimant and unlocks contact details so the two people can arrange a pickup. The claimant then generates a pickup code; the finder enters it in person; the system completes the claim and resolves both reports.

### 4.2 The four capabilities that define the product

1. **Explainable lost-versus-found matching.** After a report is saved, the system computes similarity against eligible opposite-type reports and shows the best candidates with reasons.
2. **Automatic in-app match alerts.** When a pair crosses the strong-match threshold, each report's author receives one deduplicated alert in the header inbox.
3. **Private ownership questions.** Found-item reporters ask a distinguishing question that is never part of the public report. Claimants answer privately with their claim, and the reporter reviews the answer before approving.
4. **OTP-verified handover.** A short-lived, attempt-limited pickup code proves that the claimant and finder completed the handover, not merely that a claim was approved.

### 4.3 The trust chain

The product is best understood as a chain with five links:

```
Report  →  Suggest  →  Prove  →  Decide  →  Verify pickup  →  Resolve
          (match)     (private    (human      (two-device       (both
                       question)   review)     code)             reports)
```

Each link has a different job and a different failure mode:

- **Report** must be easy enough that people actually use it.
- **Suggest** must be useful without pretending to be certain.
- **Prove** must test knowledge without publishing it.
- **Decide** must stay with a human who can judge context.
- **Verify pickup** must bind the digital record to a physical exchange.

Most lost-and-found tools stop after “Report” or “Decide”. CampusLost carries the record all the way to “Resolve” and keeps the intermediate states honest.

### 4.4 Illustrative walkthrough

The following scenario shows the intended experience. It is an illustration of the workflow, not a report of measured usage.

> Alice loses a dark blue water bottle in the library. She posts a LOST report with a photo and the location “Central Library, 2nd floor”.
>
> Bob finds a similar bottle in the library and posts a FOUND report. He writes a public description that deliberately omits the sticker on the base. In the private ownership question he asks: “What is written on the sticker under the bottle?”
>
> Matching compares the two reports. It finds a meaningful text overlap, the same category, the same location, and a one-day gap. Alice sees a suggestion with a score of 72 and reasons: “Similar item description”, “Same category”, “Same campus location”, and “Reported one day apart”. Bob sees the corresponding lost report.
>
> Alice opens the found report from the suggestion. The claim form is already linked to her lost report. She writes a short message and answers the private question. Her answer is stored as a snapshot with the claim and is visible only to Bob and to administrators.
>
> Bob opens the claim, reads the answer, and approves it. The found bottle becomes RESERVED and Alice's lost report becomes RESERVED. Other pending claims on the bottle are rejected. Contact details unlock for both participants. The interface says “Approved, awaiting pickup” — not “returned”.
>
> At the pickup, Alice opens the claim on her phone and generates a six-digit code. The code is valid for ten minutes. Bob enters it on his phone. The system verifies the code, marks the claim COMPLETED, marks both reports RESOLVED with the same timestamp, and records that the handover was verified. The reports disappear from open browsing and matching. Alice and Bob keep their history.

The same flow works in the other direction for a LOST report: a finder can respond “I found this”, and approval resolves that report directly. That response is a message, not an ownership claim, and it never receives a verified-pickup badge.

### 4.5 Design principles

Five principles explain most of the implementation decisions.

**A lead is not proof.** Matching orders possibilities; it does not grant ownership. The score is a similarity score. A human approves. A separate handover step confirms collection.

**Private by default where privacy matters.** Public report data is deliberately narrow. Email, phone, ownership prompts and answers, claimant identity, pickup codes, and code digests never appear in public responses. Private endpoints are authorized by identity derived from the authentication token, never by a client-supplied user ID.

**The server is the authority.** The browser can hide a button, but the server enforces every transition. A direct status request cannot complete a FOUND handover. A client cannot promote itself to administrator. A stale edit is rejected rather than silently overwriting newer data.

**Fail closed.** If a report has an ownership question but the evidence policy is unavailable, a claim is refused with a clear service-unavailable error rather than accepted without proof. If the handover signing key is missing, the feature fails safely instead of issuing predictable codes. If a response is not explicitly public, it is private.

**Boring, auditable mechanisms first.** The matcher is a deterministic token comparison, not an opaque model. The pickup code is a random six-digit value with a keyed digest, not a screenshot of a QR code. The lifecycle is a small set of named states with explicit transitions. This makes the system explainable to users, testable by engineers, and defensible in an evaluation.

---

## 5. User roles

CampusLost has two account roles, but the experience has several contextual roles. A person can be a finder on one report and an owner on another in the same session.

| Role | How it is obtained | What the person can do |
|---|---|---|
| **Visitor** | No account | Browse public reports, search and filter, open a report, see public metadata and photos. Cannot claim, view private questions, or see contact details. |
| **Registered user** | Self-registration | Everything a visitor can do, plus report items, edit and manage their own reports, send claims or responses, receive alerts, answer ownership questions, and view their own activity. |
| **Reporter (owner of a report)** | Creates a report | Edit the report while it is editable, attach or replace a photo, set the ownership question on a FOUND report, review claims, approve or reject, release an approved reservation, mark resolved or closed, soft-delete. |
| **Claimant** | Sends a claim on a FOUND report, or a response on a LOST report | Track the claim, cancel while pending, view private evidence they submitted, see contact details after approval, generate a pickup code for an approved FOUND claim. |
| **Finder** | Reporter of a FOUND report | Describe the found item, attach a photo, ask a private ownership question, review claimants' answers, verify a pickup code, release a reservation, resolve the report after verified pickup. |
| **Administrator** | Account with the `ADMIN` role | View dashboard statistics and all visible reports, inspect claims on any report, access evidence and contact details where authorized, verify or release handovers, delete reports, and run orphan-photo cleanup. |

Three boundaries are worth stating explicitly.

- **A reporter cannot claim their own report.** The server rejects it.
- **A finder cannot claim a found item on someone else's behalf.** On a FOUND report, the finder sees suggested LOST reports for context, but the claim action belongs to the owner of the lost report. The finder's job is to review and hand over.
- **A claimant cannot verify their own pickup.** Only the target reporter or an administrator can enter the code. The two-device rule is intentional.

---

## 6. The full user journey

### 6.1 End-to-end flow

```
        OWNER / CLAIMANT                          FINDER / REPORTER
        ────────────────                          ─────────────────
  Browse or register/log in                 Browse or register/log in
            │                                         │
            ▼                                         ▼
  Post LOST report                          Post FOUND report
  title, description, category,             title, description, category,
  location, date, optional photo            location, date, photo, and a
            │                               private ownership question
            │                                         │
            └──────────────┬──────────────────────────┘
                           ▼
                 Matching runs after commit
             up to 5 explained candidates (score ≥ 35)
             strong pairs (score ≥ 60) create alerts
                           │
             ┌─────────────┴─────────────┐
             ▼                           ▼
  Owner sees suggested FOUND       Finder sees suggested LOST
  reports with reasons             reports with reasons
             │                           │
             ▼                           │
  Opens suggestion; lostReportId         │
  is preselected on the claim form       │
             │                           │
             ▼                           │
  Sends claim + private answer ────────▶ Reviews claim and evidence
             │                           │
             │                           ▼
             │                     Approve or reject
             │                           │
             ▼                           ▼
  APPROVED: item RESERVED, contacts unlocked, other claims rejected
             │                           │
             ▼                           ▼
  Generates 6-digit pickup code    Enters code on finder's device
             │                           │
             └──────── in-person ────────┘
                           ▼
          COMPLETED: both reports RESOLVED, verified handover recorded
```

### 6.2 Step by step

**Step 1 — Arrive and browse.** A visitor lands on the browse page. The default view shows OPEN reports. Type, category, location, status, and free-text search are available, and the filters are reflected in the URL so a search can be linked or reloaded. A signed-out visitor who tries to claim is taken to login and returned to the intended report afterwards.

**Step 2 — Create an account or log in.** Registration collects name, email, optional phone, and password. Passwords are hashed with BCrypt. Login returns a signed JWT and the user profile. The client validates the stored token against `/api/auth/me` before private queries run, so a stale local profile cannot unlock private data.

**Step 3 — Post a report.** The report form collects a LOST or FOUND type, title, description, category, location, date, and an optional photo. For a FOUND report, the form also collects the private ownership question. The public copy explicitly asks finders to leave identifying details out of the public description. A report cannot change between LOST and FOUND after creation.

**Step 4 — Matching and alerts.** Saving the report commits it, then triggers matching in a separate transaction. The report's author can open a match panel on the report to see up to five explained candidates. If a pair reaches the alert threshold, each author receives one in-app alert. The header bell shows an unread count; opening the inbox shows report titles, actual reasons, time, and one destination.

**Step 5 — Open a suggestion.** A LOST author who opens a suggested FOUND report arrives with their lost report identifier in the URL and preselected on the claim form. This prevents the common mistake of claiming the right item but failing to link it to the right lost report. A FOUND author can inspect suggested LOST reports, but cannot claim the found item for someone else.

**Step 6 — Send a claim or response.** On a FOUND report, the claimant writes a message (at least 10 characters in the UI, capped at 2,000 by the server) and answers the private ownership question (10–2,000 characters when a question exists). If the item was reached from a match, the claimant's own lost report is linked automatically. On a LOST report, the response is a “I found this” message; no ownership question or pickup code is involved.

**Step 7 — Review the claim.** The reporter sees the claimant's name, message, linked lost report if any, and — for a FOUND claim — the private answer. The reporter can approve or reject. Evidence review is deliberately manual; no score, hash, or automatic classifier decides ownership.

**Step 8 — Approval reserves the item.** Approving a FOUND claim:

- sets the found report to RESERVED;
- sets the linked lost report, if any, to RESERVED;
- marks the claim APPROVED;
- rejects other pending claims on the found report and on the linked lost report;
- leaves `resolvedAt` empty on both reports;
- unlocks the participant-only contact endpoint.

The interface says “Approved, awaiting pickup”. This is the single most important semantic choice in the product: approval is a reservation, not a return.

**Step 9 — Arrange and complete the pickup.** The two participants can now see each other's name, email, and phone through an authorized, non-cached endpoint. At the handover, the claimant generates a six-digit code. The code is shown only on the claimant's device, is valid for ten minutes, and can be regenerated after a 60-second cooldown. The finder enters the code on their own device. A correct code completes the claim, marks both reports RESOLVED with the same completion timestamp, and records the verified handover. A wrong, expired, or locked code produces a specific, non-destructive error.

**Step 10 — After completion.** Open browsing and matching no longer include the resolved reports. Personal history keeps the claim, evidence, contact record, and verified timestamp. A completed handover cannot be reopened. If the pickup does not happen, the reporter can release the reservation with an optional reason; the claim becomes CANCELLED, both reports reopen, and contact details become unavailable again.

### 6.3 What each side sees

| Information | Visitor | Signed-in claimant | Target reporter / admin | Other users |
|---|---|---|---|---|
| Public title, description, category, location, date, photo, status | Yes | Yes | Yes | Yes |
| Reporter display name | Yes | Yes | Yes | Yes |
| Reporter email and phone | No | Only after approval/completion | Only after approval/completion | No |
| Claimant identity and message | No | Own claim | Claims on their report | No |
| Ownership question prompt | No | Yes, for a visible FOUND report | Yes, including while reserved | No |
| Ownership answer | No | Own answer | Answers on their report | No |
| Pickup code | No | Own generated code only | Never fetched from the server | No |
| Notifications | No | Own inbox only | Own inbox only | No |

### 6.4 Alternate journeys

**Rejecting a claim.** A pending claim can be rejected by the reporter or an administrator. The rejection is kept in history. The claimant may submit a new claim while the report is open; resubmission creates a new claim rather than reviving the old one.

**Cancelling a claim.** A claimant may cancel their own claim while it is PENDING. Approved reservations cannot be cancelled by the claimant; they must be released by the reporter or an administrator. This prevents a claimant from walking away while the item is still reserved.

**Releasing a reservation.** A reporter or administrator can release an approved FOUND reservation with a reason of up to 500 characters. The claim becomes CANCELLED, the release is recorded, and both the found report and any linked lost report return to OPEN. Contact details are hidden again. This is the recovery path for a mistaken approval or a no-show.

**Manual resolution.** A reporter can mark an OPEN report RESOLVED without a claim. This rejects pending claims and carries no verified badge. A manually resolved report can be reopened, which clears the resolution timestamp, unless a verified handover has already completed. CLOSED is terminal.

**Soft deletion.** Deleting a report hides it from browsing, matches, and public photo serving while preserving claim history. Reserved reports must be released before deletion. Photos attached to a deleted report stop being publicly accessible.

**Legacy completion.** Older claims that were approved on already resolved items are migrated to COMPLETED so that history is preserved. They do not receive a verified-pickup badge, because no handover record exists. The interface describes them honestly as resolved without pickup verification.

---

## 7. Features in detail

### 7.1 Structured lost and found reports

**What it does.** A signed-in user creates a LOST or FOUND report with a type, title, description, category, location, date, and optional photo. The report belongs to the authenticated user and starts in OPEN.

**How it works.** The backend validates required text, trims input, checks the category against a fixed list, rejects dates in the future beyond a one-day tolerance, and rejects dates before 2000. Titles are capped at 120 characters, descriptions at 4,000, categories at 60, and locations at 120. Report type is immutable after creation. Edits use a PATCH body containing the current `version`; if another change has already incremented the version, the edit is rejected with `409 STALE_VERSION`.

**Why it matters.** Consistent metadata is what makes matching and filtering possible. A free-form notice board cannot compare two reports; a structured record can.

**Photo handling.** A photo is uploaded separately, returns an opaque identifier and URL, and is attached to the report at create or edit time. The server accepts JPEG, PNG, or WebP up to 5 MiB and 12,000 pixels per side, decodes the image, and re-encodes it to strip EXIF, GPS, and embedded payloads. Files are stored under random server names; user filenames are never used. A photo can be attached only by its uploader, and only to one report. Public serving is restricted to photos attached to a visible report. Unattached uploads and photos of soft-deleted reports return 404.

### 7.2 Browse, search, and filters

**What it does.** The home page lists reports with type tabs, free-text search, location, category, and status filters. The default status is OPEN. The result count is shown, and empty states distinguish “no matches” from “nothing here yet”.

**How it works.** Filters are held in validated URL search parameters, so a search survives a refresh and can be shared. Text search matches title, description, or location, case-insensitively. Category is normalized against the allowed list. Status and type are enum-validated. The query returns visible, non-deleted reports ordered newest first. A malformed enum returns `400 INVALID_PARAMETER` rather than silently ignoring the filter.

**Why it matters.** Search is the fallback when matching misses. It also gives administrators and visitors a complete public inventory without exposing private fields.

### 7.3 Claims and responses

**What it does.** A signed-in user can claim a FOUND report or respond to a LOST report. The reporter reviews the response and approves or rejects it. Claims have a full history: pending, approved, rejected, cancelled, and completed.

**How it works.** Claim creation checks that the target is visible and OPEN, that the claimant is not the reporter, and that the claimant has no existing active claim on that report. A FOUND claim may link one of the claimant's own OPEN LOST reports; the server verifies ownership, type, status, and that no other approved claim already reserves that lost report. A LOST response cannot link a lost report and cannot carry an ownership answer. Duplicate active claims are rejected. Pending claims can be cancelled by the claimant. Approval and rejection are restricted to the target reporter or an administrator.

**Why it matters.** A claim is the social contract of the system. It records who asked, what they said, what was decided, and when. Keeping rejected and cancelled history means a reporter can see repeated attempts without being blocked from a legitimate later one.

### 7.4 Explainable lost-versus-found matching

**What it does.** After a report is saved, its author can see up to five opposite-type reports that may be the same item. Each suggestion includes the candidate report's public summary, a similarity score from 0 to 100, and a short list of reasons. On a LOST report, opening a suggested FOUND report preselects the author's lost report on the claim form. On a FOUND report, the finder can inspect suggested LOST reports for context.

**How it works.** The matcher uses local, deterministic text retrieval. It normalizes case and punctuation, removes common stop words, compares title and description tokens with the title weighted more heavily, and combines that with exact category, normalized location similarity, and date proximity. A candidate must clear a minimum text-similarity threshold before any other signal can make it eligible. Same-reporter pairs, same-type pairs, and reports that are not OPEN or have been deleted are excluded. The score is rounded to an integer, and ties are broken by score descending and then candidate identifier ascending. Results are persisted as a canonical LOST/FOUND pair snapshot with both source versions. See [section 13](#13-how-matching-scores-and-explains) for the formula.

**Why it is novel in this product.** The matcher is not a black box and not an ownership oracle. It exposes the ingredients of its judgement, refuses to match on category or location alone, and never sees private questions, answers, contacts, pickup codes, or image metadata. That makes the suggestion defensible to a user and auditable by an evaluator.

### 7.5 Automatic in-app match alerts

**What it does.** When a LOST/FOUND pair reaches a strong similarity threshold, each report's author receives one in-app notification. The header shows an unread badge. Opening the bell reveals a compact inbox with the report title, the actual match reasons, the time, and a single destination. Read state survives a refresh.

**How it works.** Matching publishes an immutable `MatchCandidatesChangedEvent` containing the current eligible candidates for a report. The notifications module consumes the event after commit in a new transaction. For each pair at or above the alert threshold, it upserts one row per recipient using a deterministic deduplication key of the form `match:<lostId>:<foundId>`. Replayed events, concurrent delivery, score changes, and application restarts do not create duplicates or reset the read timestamp. When a pair falls below the threshold or becomes ineligible, the existing alert is deactivated rather than deleted, so history remains consistent. Eligibility is checked again at read time, so a delayed listener cannot expose an alert for a report that is now reserved, resolved, closed, or deleted.

The inbox supports paging with `limit` and `beforeId`, read-one, read-all, and an unread count. Notifications are scoped to the authenticated recipient; the server never accepts a recipient ID from the client. Navigation is constructed from fixed routes and IDs, never from a caller-supplied URL. The bell polls every 20 seconds while the user is signed in and the page is visible, refreshes on focus, and stops on logout or when the tab is hidden. Badge state is cleared on account switch so one user's unread count never flashes for another.

**Why it is novel in this product.** Most lost-and-found tools are passive: the user must return and search. CampusLost turns a committed match into a delivered, deduplicated, privacy-aware alert, while keeping the alert honest about what it means. “A strong match was found” is not “your item is ready”.

Status alerts use the same mechanism for claim approval, rejection, and verified handover. A handover-completed alert requires the verified flag, not merely a COMPLETED status, so legacy completions are not misrepresented.

### 7.6 Private ownership questions and evidence

**What it does.** A finder attaches one private question to a FOUND report, such as “What is engraved under the lid?” or “Describe the wallpaper without unlocking the phone.” A signed-in claimant sees the question and submits an answer privately with the claim. The finder reviews the answer before approving.

**How it works.** The question is stored on the item but is never included in the public item DTO. New FOUND reports require a trimmed question of 10–200 characters. LOST reports cannot carry a question. Existing reports without a question remain usable and show an honest “No question supplied” state for the reviewer.

The claimant fetches the prompt through an authenticated endpoint that is available only for a visible FOUND item. The endpoint returns the prompt and whether it is required; it never returns previous answers. A claim captures an exact snapshot of the current prompt together with the answer inside the claim transaction and under the item lock. This is important: if the reporter later edits the prompt, the stored evidence for an existing claim does not change. Question edits are frozen while pending claims exist, so a reviewer is not comparing answers to a moving target. Answers are 10–2,000 characters when a question exists; a supplied answer on a LOST response is rejected.

Evidence reads are restricted to the claimant, the target reporter, and administrators, with no-store headers. Evidence is excluded from public DTOs, matching, events, notifications, logs, and other claimants' screens. Rejection or cancellation keeps the evidence in private history and does not prevent a new attempt.

**Why it is novel in this product.** The question is a challenge-response protocol expressed in ordinary language. It gives the finder a signal that the claimant knows something only the owner would know, without publishing the secret, without storing an answer hash as a fake proof, and without granting ownership automatically. Review stays human; the system supplies private evidence, not a verdict.

### 7.7 OTP-verified handover

**What it does.** For an approved FOUND claim, the claimant generates a six-digit pickup code on their own device. The finder enters that code on their device at the handover. Only a successful verification completes the claim and resolves both reports. The finder never fetches the code from the server, and there is no bypass button, including for administrators.

**How it works.** A per-claim handover challenge stores a keyed HMAC digest of the code, an expiry, a failed-attempt counter, a next-issue time, and a verified timestamp. The plaintext code is returned only in the issuance response and is kept in component memory; a reload requires regeneration after the cooldown. Codes are six cryptographically random digits, valid for ten minutes, with five failed verification attempts and a 60-second issuance cooldown. Regeneration replaces the previous digest, so an old code stops working immediately.

Verification takes the shared locked claim context first, then locks the challenge row, and performs the check in one transaction. The digest is bound to the claim identifier and generation as well as the code, and comparison is constant-time. A wrong or expired code is a controlled 400 with attempts remaining. Exhausted attempts or a cooldown is a 429 with `Retry-After`. A wrong lifecycle is a 409. Failed attempts are persisted even when the request returns an error; a failure during completion cannot leave one report resolved and the other reserved. Concurrent correct verifications produce one transition and one event; a repeated successful request by the authorized verifier returns the existing completed result without emitting another event.

The handover status endpoint returns `NOT_ISSUED`, `READY`, `EXPIRED`, `LOCKED`, or `COMPLETED`, plus expiry, attempts remaining, and verification time. It never returns the code or digest. The release endpoint uses the core reservation release path, reopens both reports, invalidates the challenge, and provides a recovery route after a mistaken approval or no-show.

**Why it is novel in this product.** The code turns a digital approval into evidence of a physical exchange. Because the claimant generates and the finder verifies on separate devices, one person cannot complete the handover alone. Because the digest is keyed and tied to the claim and generation, a database reader cannot brute-force a six-digit code from a plain hash. Because completion runs inside the same workflow that owns the report locks, the record and the report states cannot diverge.

### 7.8 Account activity, administration, and operational safety

**My activity.** A signed-in user sees the reports they posted and the responses they sent, with status badges, pending-response counts, and contextual next steps. They can edit, resolve, or delete their own reports where allowed, and follow a claim into its detail page.

**Admin dashboard.** Administrators see counts for items, open, reserved, resolved, closed, claims, and users, plus a table of all visible reports and their statuses. They can open any report, review claims, access evidence and contacts where authorized, verify or release a handover, and soft-delete a report. They can also run an explicit cleanup of unattached photos older than 24 hours.

**Operational safety.** The API is stateless, configuration is environment-driven, secrets live outside Git, Flyway owns the schema, Hibernate runs in validate mode, private responses are marked `no-store, private`, and errors use a stable `{code, message, fieldErrors?}` shape. The frontend distinguishes loading, empty, error, and success states, preserves form input after a failed submission, and announces status changes accessibly.

---

## 8. What makes CampusLost novel

“Novel” here means a distinctive contribution for a college IP evaluation, not a claim that no similar component exists anywhere. The novelty is in the composition and the trust model.

### 8.1 The integrated trust chain

Most campus lost-and-found systems are notice boards with a search box. Some add a claim button. Very few connect discovery, explanation, private proof, human review, and physical handover into one lifecycle. CampusLost does, and each link has a defined artefact:

| Link | Artefact | What it proves |
|---|---|---|
| Discovery | Score + reasons | Why this report is worth opening |
| Proof | Private question + answer snapshot | The claimant knows a withheld detail |
| Decision | Claim decision with actor and timestamp | A human accepted or rejected the evidence |
| Handover | Keyed, expiring, attempt-limited code | Two devices confirmed the exchange |
| Resolution | Claim COMPLETED + reports RESOLVED with equal timestamps | The item left the open inventory |

That chain is the core intellectual contribution. It turns a social process into a record that can be inspected, disputed, and audited.

### 8.2 Explainability without a model dependency

The matcher is deliberately transparent. It publishes its weights, its eligibility gate, its thresholds, and its reason strings. A user can see why a candidate appeared. An engineer can reproduce the score from the two reports. An evaluator can test the ranking with fixtures. The system does not require a model download, an API key, a GPU, or a network call, which makes it usable on a campus laptop and honest about its limits.

This is a deliberate trade-off. A large language model or image embedding might find more subtle matches, but it would be harder to explain, harder to test deterministically, and dependent on an external service. CampusLost chooses reproducibility and trust for this delivery, and leaves richer similarity as future work.

### 8.3 Privacy as a protocol, not a policy page

The private question is not merely hidden in the UI. It is excluded from the public DTO, from matching inputs, from events, from notifications, and from logs. The answer is stored as a snapshot tied to the claim, not re-read from the current item. Evidence reads are authorized by identity and restricted to three parties. Contacts unlock only after approval and disappear on release. Pickup codes never enter URLs, notifications, analytics, or logs, and only a keyed digest is stored.

This is privacy by construction: the data model and endpoint boundaries make the wrong exposure difficult, rather than relying on a frontend developer to remember to hide a field.

### 8.4 Correct lifecycle semantics

The distinction between RESERVED and RESOLVED is small in code and large in meaning. Approval reserves. Verified pickup resolves. Release reopens. Manual resolution is honest about lacking a verified badge. A completed handover cannot be reopened. A linked lost report participates in at most one approved claim. These rules prevent the two most common lies in lost-and-found software: “approved means returned” and “resolved means verified”.

### 8.5 Engineering rigour appropriate to the problem

The lifecycle engine takes pessimistic locks on items in ascending ID order, then locks the claim or challenge, re-reads state under lock, and runs at READ COMMITTED so a locking read sees the newest committed row. Optimistic `version` fields reject stale edits. Generated nullable columns plus unique keys enforce one active reservation per report at the database level. Events carry IDs and versions, not managed entities or secrets. Feature modules consume events after commit in their own transactions. This is the kind of correctness work that a demo can hide and a real handover cannot.

---

## 9. System architecture

### 9.1 High-level architecture

```
┌──────────────────────────────── Browser ────────────────────────────────┐
│  React 19 + TanStack Start (SSR) + TanStack Router + TanStack Query     │
│  Tailwind CSS v4 + Base UI + Phosphor icons                             │
│                                                                         │
│  Browse / search   Report form   Item detail   My activity   Admin      │
│  Match panel       Alert bell    Ownership fields   Handover panel      │
└──────────────────────────────────┬──────────────────────────────────────┘
                                   │  HTTPS, JSON, Bearer JWT
                                   │  same-origin /api proxy in development
┌──────────────────────────────────▼──────────────────────────────────────┐
│  Spring Boot 4.1 application (stateless)                                │
│                                                                         │
│  Security filter chain                                                  │
│    JWT authentication → login/register rate limit → private cache       │
│                                                                         │
│  Controllers  →  Services  →  ClaimWorkflow  →  Repositories (JPA)      │
│       │              │              │                                   │
│       │              │              └─ locks, transitions, events       │
│       │              └─ item rules, photos, auth                        │
│       └─ HTTP contracts, validation, authorization                      │
│                                                                         │
│  Feature modules                                                        │
│    matching        notifications        ownership        handover       │
│                                                                         │
│  Domain events published inside the writing transaction                 │
│    ItemChangedEvent, ClaimChangedEvent, MatchCandidatesChangedEvent     │
└───────────────┬──────────────────────────────────┬──────────────────────┘
                │                                  │
                ▼                                  ▼
      MariaDB 11 (InnoDB)                  Local photo directory
      Flyway-managed schema                (outside Git, opaque names)
```

### 9.2 Frontend architecture

The frontend is a TanStack Start application. Routes are file-based: browse, report, item detail, item edit, login, register, my activity, and admin. TanStack Router validates search parameters for filters and the optional `lostReportId`, so a link from a match can carry the intended lost report through login and into the claim form. TanStack Query owns server state. Public item queries and account-scoped private queries use separate keys (`['public', ...]` and `['account', userId, ...]`), which makes cache isolation between accounts explicit. A shared `invalidateWorkflow` helper refreshes public reports, account reports, claims, evidence, matches, contacts, admin counts, and notifications after a mutation.

Authentication is a React context. A stored token is treated as a hint, not proof: the app calls `/api/auth/me` once before enabling private queries. On login or logout, it cancels in-flight queries and clears the cache before switching identity. On a 401, it clears the session and prompts login once. SSR creates one query client per request, avoiding cross-request cache leakage.

Feature UI is isolated in four directories with typed entrypoints: matching, notifications, ownership, and handover. The item detail page mounts the match panel for reporters and administrators, the alert bell sits in the header, the create/edit forms mount the ownership question field, the claim form mounts the answer field, and approved claims mount the handover panel. This keeps feature-specific styles and types inside their own modules while the shared pages stay stable.

### 9.3 Backend architecture

The backend is a layered Spring Boot application:

- **Security layer** — stateless JWT authentication, JSON 401/403 handlers, CORS, login/register rate limiting, and a filter that marks private API responses `no-store`.
- **Controller layer** — thin HTTP adapters under `/api`, with validation and authenticated principal injection.
- **Service layer** — item rules, authentication, claims, photos, and feature services.
- **Workflow layer** — `ClaimWorkflow` is the single place where report and claim state transitions happen. It acquires locks, checks permissions and state, performs the transition, flushes, and publishes events.
- **Repository layer** — Spring Data JPA repositories with explicit locking queries and search queries.
- **Domain model** — JPA entities with optimistic `version` fields, soft deletion, and enum statuses.
- **Feature modules** — new packages for matching, notifications, ownership, and handover, each with its own repositories, services, controllers, and tests.
- **Contract layer** — immutable event records and optional policy interfaces shared between core and features.

The application is stateless: no HTTP session, no server-side login state. Each request authenticates from the JWT, loads the user, and proceeds. This makes restarts safe and horizontal scaling possible in principle, although the current deployment is a single instance.

### 9.4 Request flow

```
Browser
  │  fetch /api/items/42/claims  +  Authorization: Bearer <JWT>
  ▼
Security filter chain
  │  parse token → load user → set authentication
  │  apply route rules (public vs authenticated)
  ▼
Controller
  │  validate JSON, inject principal
  ▼
Service / ClaimWorkflow
  │  open transaction
  │  lock item(s) in ascending ID order
  │  re-read state
  │  check permission, status, version
  │  write change + flush
  │  publish domain event
  ▼
JPA / Hibernate
  │  SQL against MariaDB
  ▼
Response
  │  JSON DTO (never an entity)
  │  private responses: Cache-Control: no-store, private
  ▼
Browser
  │  update query cache, invalidate dependent views
```

### 9.5 Event-driven feature flow

```
Report write (create / edit / status / delete)
        │
        │  same transaction
        ▼
ItemChangedEvent(itemId, version, kind)
        │
        │  after commit, new transaction
        ▼
Matching recomputes affected pairs
        │
        ├─ update match_candidates snapshots
        │  (complete replacement, both source versions)
        │
        ▼
MatchCandidatesChangedEvent(itemId, version, candidates)
        │
        │  after commit, new transaction
        ▼
Notifications upsert / deactivate alerts
        │
        ▼
Header bell polls the recipient's inbox
```

The separation matters. Matching failure must not roll back a valid report, and notification failure must not roll back a match. Each consumer runs in its own transaction, uses committed IDs and versions, and rejects stale source versions. Matching also performs a bounded catch-up refresh of open reports when the application becomes ready, so a restart or a late-installed consumer can backfill current snapshots.

### 9.6 Deployment topology

The current deployment is intentionally simple:

- One frontend process (TanStack Start/Vite), proxying `/api` to the backend.
- One stateless Spring Boot process.
- One MariaDB 11 database with a separate test database.
- One local photo directory outside Git.
- Secrets in a local, git-ignored properties file with mode 600.
- CORS restricted to configured local origins, including the portless campus origin used in development.

This is appropriate for a campus pilot and a college evaluation. The current topology is single-node; see [section 17](#17-limitations-and-future-scope).

---

## 10. Technology stack and reasons

### 10.1 Backend

| Technology | Version / choice | Why it is used |
|---|---|---|
| **Java** | 25 (pinned Eclipse Temurin 25.0.5+8 via a checked bootstrap) | Modern LTS language and runtime, reproducible builds, and a supported toolchain for the selected Spring Boot version. The build pins the JDK rather than relying on the system default. |
| **Spring Boot** | 4.1.0 | Mature dependency injection, web MVC, validation, security, JPA integration, and production defaults with little boilerplate. |
| **Spring Security** | Part of Spring Boot 4.1 | Stateless JWT authentication, route authorization, JSON 401/403 handling, CORS, and password encoding. |
| **JJWT** | 0.12.6 | Small, well-understood JWT creation and verification library with explicit key handling. |
| **Spring Data JPA / Hibernate** | Hibernate 7.4.5.Final | Entity mapping, derived queries, pessimistic and optimistic locking, and a clear repository abstraction. Hibernate runs with `ddl-auto: validate`, so the schema is owned by migrations rather than guessed at runtime. |
| **MariaDB** | 11+ (InnoDB) | Reliable relational storage with transactions, row locks, generated columns, and unique constraints — all needed for reservation correctness. |
| **Flyway** | Spring Boot integration | Versioned, repeatable schema evolution with validation. Existing databases are baselined at V1 and upgraded, not reset. |
| **BCrypt** | Spring Security `BCryptPasswordEncoder` | Adaptive password hashing with a per-password salt. |
| **TwelveMonkeys ImageIO** | 3.15.3 | WebP decoding support so the server can validate and re-encode WebP uploads. |
| **JUnit 5 / Spring Boot Test / MockMvc / AssertJ** | Spring Boot 4.1 test stack | Service-level integration tests against MariaDB, HTTP contract tests, and readable assertions. |
| **Maven Wrapper** | `./mvnw` | Reproducible builds without a globally installed Maven. |

### 10.2 Frontend

| Technology | Version | Why it is used |
|---|---|---|
| **React** | 19.2.8 | Component model and ecosystem; concurrent rendering support. |
| **TanStack Start** | 1.169.3 | Full-stack React framework with SSR, file-based routing, and server integration. |
| **TanStack Router** | 1.170.45 | Type-safe routes and validated search parameters, including filters and `lostReportId`. |
| **TanStack Query** | 5.104.5 | Server-state caching, request cancellation, account-scoped keys, and centralized invalidation. |
| **Vite** | 8.3.5 | Fast development server and production build. |
| **TypeScript** | 6.0.3 | Compile-time contracts for API payloads, feature props, and route search. |
| **Tailwind CSS** | 4.3.3 | Consistent spacing, color, and responsive layout without a large bespoke stylesheet. |
| **Base UI** | 1.6.0 | Accessible primitives for dialogs, selects, tabs, popovers, and other controls. |
| **Phosphor Icons** | 2.1.10 | Consistent icon set with weight and size control. |
| **date-fns** | 4.4.0 | Small, tree-shakeable date formatting and arithmetic. |
| **pnpm** | 12.14.0 | Fast, strict, lockfile-based dependency installation. |
| **Node.js** | 22+ | Runtime for the frontend toolchain. |

### 10.3 Deliberate non-choices

| Not used | Why not, for this delivery |
|---|---|
| External LLM or embedding API | Would add a key, cost, latency, non-determinism, and a privacy surface. Deterministic matching is explainable and offline. |
| Redis or a message broker | The event flow runs in-process with Spring events and after-commit transactions. A broker is unnecessary at this scale. |
| Server-side sessions | A stateless JWT keeps the API simple and restart-safe. |
| A separate mobile app | The responsive web app works on phones, which is where the pickup code is shown. |
| Cloud object storage | Local photo storage is sufficient for a campus pilot and keeps the setup self-contained. |
| A CSS framework with a heavy runtime | Tailwind plus Base UI keeps the bundle and the design system understandable. |

---

## 11. Data model

### 11.1 Entity relationship overview

```
┌──────────┐        ┌──────────┐        ┌──────────┐
│  users   │1      *│  items   │1      *│  claims  │
│──────────│────────│──────────│────────│──────────│
│ id       │        │ id       │        │ id       │
│ name     │        │ type     │        │ item_id  │──┐
│ email    │        │ status   │        │ lost_    │  │ optional link
│ password │        │ reporter │        │ report_id│──┘ to another item
│ phone    │        │ photo_id │        │ claimant │
│ role     │        │ question │        │ status   │
└────┬─────┘        └────┬─────┘        │ answer   │
     │                   │              │ version  │
     │                   │              └────┬─────┘
     │                   │                   │
     │              ┌────▼─────┐        ┌────▼──────────────┐
     │              │  photos  │        │ handover_         │
     │              │──────────│        │ challenges        │
     │              │ id (UUID)│        │ claim_id (unique) │
     │              │ uploader │        │ code_digest       │
     │              │ attached │        │ expires_at        │
     │              └──────────┘        │ failed_attempts   │
     │                                  │ next_issue_at     │
     │                                  │ verified_at       │
     │                                  └───────────────────┘
     │
     │        ┌──────────────────┐      ┌──────────────────┐
     └───────*│ notifications    │      │ match_candidates │
              │ recipient_id     │      │ lost_item_id     │
              │ type, dedupe_key │      │ found_item_id    │
              │ item/claim refs  │      │ score, reasons   │
              │ active, read_at  │      │ source versions  │
              └──────────────────┘      └──────────────────┘
```

### 11.2 Core tables

#### `users`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT, identity | Primary key. |
| `name` | VARCHAR(100) | Display name. |
| `email` | VARCHAR(150), unique | Stored lowercase; login identifier. |
| `password_hash` | VARCHAR(255) | BCrypt hash. Never returned by any DTO. |
| `phone` | VARCHAR(30), nullable | Private; released only through the contact endpoint. |
| `role` | VARCHAR(20) | `USER` or `ADMIN`. |
| `created_at` | DATETIME(6) | Account creation time. |

#### `items`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT, identity | Primary key. |
| `type` | VARCHAR(10) | `LOST` or `FOUND`; immutable after creation. |
| `title` | VARCHAR(120) | Required. |
| `description` | TEXT | Required, up to 4,000 characters. |
| `category` | VARCHAR(60) | One of the allowed campus categories. |
| `location` | VARCHAR(120) | Free text, searchable. |
| `date_occurred` | DATETIME(6) | When the item was lost or found; not in the future. |
| `status` | VARCHAR(32) | `OPEN`, `RESERVED`, `RESOLVED`, `CLOSED`. |
| `reporter_id` | BIGINT | Foreign key to `users`. |
| `created_at` | DATETIME(6) | Posting time. |
| `updated_at` | DATETIME(6) | Last edit time. |
| `resolved_at` | DATETIME(6), nullable | Set on resolution, cleared on reopen. |
| `deleted_at` | DATETIME(6), nullable | Soft-delete marker; hidden from public reads and matches. |
| `photo_id` | VARCHAR(36), nullable | Foreign key to `photos`. |
| `ownership_question` | VARCHAR(200), nullable | Private prompt for FOUND reports; never in the public DTO. |
| `version` | BIGINT | Optimistic locking version. |

#### `claims`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT, identity | Primary key. |
| `item_id` | BIGINT | Target report. |
| `lost_report_id` | BIGINT, nullable | Optional linked LOST report owned by the claimant. |
| `claimant_id` | BIGINT | Foreign key to `users`. |
| `message` | TEXT | Required, up to 2,000 characters. |
| `status` | VARCHAR(32) | `PENDING`, `APPROVED`, `REJECTED`, `CANCELLED`, `COMPLETED`. |
| `ownership_question_snapshot` | VARCHAR(200), nullable | Exact prompt seen when the claim was created. |
| `ownership_answer` | TEXT, nullable | Private answer; never returned in claim lists. |
| `created_at` | DATETIME(6) | Claim creation time. |
| `decided_at` | DATETIME(6), nullable | Approval or rejection time. |
| `completed_at` | DATETIME(6), nullable | Verified pickup time; also used for legacy completion. |
| `handover_verified` | BOOLEAN | True only when the shared workflow completed the claim through OTP verification. |
| `release_reason` | VARCHAR(500), nullable | Why a reservation was released. |
| `released_at` | DATETIME(6), nullable | Release time. |
| `version` | BIGINT | Optimistic locking version. |
| `active_item_id` | Generated BIGINT, nullable | Equals `item_id` only for APPROVED/COMPLETED; unique. |
| `active_lost_report_id` | Generated BIGINT, nullable | Equals `lost_report_id` only for APPROVED/COMPLETED; unique. |

The two generated columns are a database-level guarantee, not just application logic. They allow unlimited rejected, cancelled, and completed history while making it impossible for two active reservations to exist on the same report or the same linked lost report.

#### `photos`

| Column | Type | Notes |
|---|---|---|
| `id` | VARCHAR(36) | Opaque UUID exposed in URLs. |
| `uploader_id` | BIGINT | Owner of the upload. |
| `storage_name` | VARCHAR(120) | Random server filename. |
| `content_type` | VARCHAR(40) | Re-encoded JPEG or PNG. |
| `size_bytes` | BIGINT | Stored file size. |
| `width`, `height` | INT, nullable | Decoded dimensions. |
| `created_at` | DATETIME(6) | Upload time. |
| `attached_item_id` | BIGINT, nullable | Set only when attached to a report. |

### 11.3 Feature tables

#### `match_candidates`

| Column | Notes |
|---|---|
| `id` | Primary key. |
| `lost_item_id`, `found_item_id` | Canonical pair; unique together. |
| `score` | Integer 0–100. |
| `reasons` | Serialized list of positive reason strings. |
| `lost_item_version`, `found_item_version` | Source versions used to compute the snapshot. |
| `updated_at` | Last recomputation time. |

#### `notifications`

| Column | Notes |
|---|---|
| `id` | Primary key. |
| `recipient_id` | The only user allowed to read or mark the row. |
| `type` | `MATCH_FOUND`, `CLAIM_APPROVED`, `CLAIM_REJECTED`, `HANDOVER_COMPLETED`. |
| `dedupe_key` | Deterministic key; unique together with `recipient_id`. |
| `lost_item_id`, `found_item_id`, `claim_id` | Navigation references; only the relevant ones are set. |
| `source_item_versions` | Versions used to validate freshness. |
| `score`, `reasons` | Snapshot for match alerts. |
| `active` | False when the pair is no longer eligible; history remains. |
| `created_at`, `read_at` | Delivery and read state. |

#### `handover_challenges`

| Column | Notes |
|---|---|
| `id` | Primary key. |
| `claim_id` | Unique; one current challenge per claim. |
| `code_digest` | Keyed HMAC of the code, bound to claim and generation. |
| `expires_at` | Ten minutes after issuance. |
| `failed_attempts` | Incremented even when verification fails. |
| `next_issue_at` | Enforces the 60-second cooldown. |
| `verified_at` | Set on successful verification. |
| `generation` / `version` | Invalidates older codes and supports optimistic checks. |

### 11.4 Indexes and constraints

- Unique email on `users`.
- Foreign keys from items to reporters and photos, from claims to items and claimants, from photos to uploaders and attached items, and from challenges to claims.
- Public browse index on `(status, deleted_at, created_at)`, plus location and category indexes.
- Claim lookup indexes on `(item_id, status)` and `(lost_report_id, status)`.
- Unique active-reservation constraints described above.
- Unique `(recipient_id, dedupe_key)` on notifications.
- Unique `claim_id` on handover challenges.

### 11.5 Data retention and deletion

Reports are soft-deleted: `deleted_at` hides them from public reads, matches, and public photos while preserving claim history. Photos are deleted when detached or replaced; an administrator cleanup removes unattached uploads older than 24 hours. Claims and evidence remain as private history after rejection, cancellation, or release. Notifications can be deactivated but are not silently rewritten. Audit-relevant timestamps (`decided_at`, `completed_at`, `verified_at`, `released_at`) are stored explicitly rather than inferred.

---

## 12. Key API endpoints

All paths are under `/api`. Request and response bodies are JSON except photo upload. IDs are numeric except photo IDs, which are opaque strings. Timestamps are ISO-8601 UTC. Errors use `{code, message, fieldErrors?}`.

### 12.1 Authentication

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/auth/register` | Public | Create an account; returns a JWT and profile. |
| POST | `/auth/login` | Public | Authenticate; rate limited. |
| GET | `/auth/me` | JWT | Validate the token and return the current profile. |

### 12.2 Reports

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/items` | Public | List and search visible reports (`q`, `type`, `category`, `location`, `status`). |
| GET | `/items/{id}` | Public | Public report detail. |
| GET | `/items/{id}/edit` | Reporter/admin | Editable snapshot including `version` and private question. |
| POST | `/items` | JWT | Create a report. |
| PATCH | `/items/{id}` | Reporter/admin | Edit with optimistic `version`. |
| PATCH | `/items/{id}/status` | Reporter/admin | Lifecycle transition. |
| DELETE | `/items/{id}` | Reporter/admin | Soft delete. |
| GET | `/items/me/reported` | JWT | Reports created by the current user. |
| GET | `/items/{id}/matches` | Reporter/admin | Explained matching candidates for this report. |
| GET | `/items/{id}/ownership-question` | JWT | Private prompt for a visible FOUND report. |

### 12.3 Claims and evidence

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/items/{id}/claims` | JWT | Claim a FOUND report or respond to a LOST report. |
| GET | `/items/{id}/claims` | Reporter/admin | Claims on a report. |
| GET | `/me/claims` | JWT | Claims sent by the current user. |
| DELETE | `/claims/{id}` | Claimant | Cancel a pending claim. |
| PATCH | `/claims/{id}` | Reporter/admin | Approve or reject. |
| POST | `/claims/{id}/release` | Reporter/admin | Release an approved reservation with a reason. |
| GET | `/claims/{id}/contact` | Participant/admin | Contact details after approval or completion. |
| GET | `/claims/{id}/evidence` | Claimant/reporter/admin | Private prompt and answer for one claim. |

### 12.4 Handover

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/claims/{id}/handover/code` | Claimant | Generate a six-digit pickup code. |
| GET | `/claims/{id}/handover` | Participant/admin | Handover state, expiry, and attempts remaining. |
| POST | `/claims/{id}/handover/verify` | Target reporter/admin | Verify the code and complete the handover. |
| POST | `/claims/{id}/handover/release` | Target reporter/admin | Release the reservation through the core workflow. |

### 12.5 Notifications

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/me/notifications?limit=20&beforeId=...` | JWT | Paged inbox with unread count. Limit max 50. |
| PATCH | `/me/notifications/{id}/read` | JWT | Mark one notification read; idempotent. |
| POST | `/me/notifications/read-all` | JWT | Mark the current user's current rows read. |

### 12.6 Photos and administration

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/photos` | JWT | Upload a photo; returns `{id, url}`. |
| GET | `/photos/{id}` | Public | Serve a photo attached to a visible report. |
| GET | `/admin/stats` | Admin | Dashboard counts. |
| GET | `/admin/items` | Admin | All visible reports. |
| GET | `/admin/items/{id}/claims` | Admin | Claims for any report. |
| POST | `/admin/photos/cleanup` | Admin | Remove unattached photos older than 24 hours. |

### 12.7 HTTP conventions

| Status | Meaning in this API |
|---|---|
| 200 | Successful read or update. |
| 201 | Created report, claim, or photo. |
| 204 | Successful delete. |
| 400 | Malformed input, invalid enum, invalid date, failed evidence validation. |
| 401 | Missing, invalid, or expired credentials. |
| 403 | Authenticated but not allowed (wrong role, not the reporter, contact not yet available). |
| 404 | Missing or inaccessible resource (including another user's notification). |
| 409 | State or version conflict (stale edit, duplicate claim, item reserved, handover not ready). |
| 413 | Upload too large. |
| 429 | Login/register rate limit, photo daily limit, or handover attempt/cooldown limit. |
| 503 | A required feature is unavailable and the request fails closed. |

Private responses carry `Cache-Control: no-store, private`. Public item lists and details and public photos do not. The contact endpoint and handover issuance endpoint are explicitly non-cacheable.

---

## 13. How matching scores and explains

### 13.1 Purpose and posture

Matching answers one question: **which opposite-type report is most likely to describe the same physical item?** It does not answer “who owns this?”. The output is a ranked shortlist with reasons, not a decision.

The score is called a **similarity score**. It is not a probability, not a confidence percentage, and not evidence of ownership. A high score cannot approve a claim, unlock contact details, or complete a handover.

### 13.2 Inputs

Matching uses only public report fields:

- title;
- description;
- category;
- location;
- date occurred;
- report status and deletion state (for eligibility).

It explicitly excludes:

- the ownership question and answer;
- contact details;
- pickup codes and digests;
- photo metadata, EXIF, and pixels;
- claimant identity and claim messages.

This keeps the matching surface understandable and prevents a private proof from leaking into a ranking that other users can infer.

### 13.3 Text normalization and comparison

The text pipeline is:

1. Normalize case and punctuation.
2. Remove common stop words.
3. Tokenize the title and description.
4. Compare the two reports using a token-based cosine-style similarity.
5. Weight title tokens more heavily than description tokens, because a title is a deliberate summary and a description contains more incidental words.

The exact implementation is deterministic: the same two reports produce the same text score on every run, with no model, sampling, or external call.

### 13.4 Weighted score

The score combines four signals. The weights are fixed and documented:

| Signal | Weight | Notes |
|---|---:|---|
| Text similarity | 60% | Title-weighted token comparison. |
| Exact category | 20% | Full credit only when the categories match exactly. |
| Location similarity | 15% | Normalized comparison of the location strings. |
| Date proximity | 5% | Full credit at the same date, decreasing linearly to zero at 14 days. |

The raw weighted result is multiplied by 100 and rounded to an integer from 0 to 100.

```
score = round(100 × (
    0.60 × textSimilarity
  + 0.20 × categoryMatch
  + 0.15 × locationSimilarity
  + 0.05 × dateProximity
))
```

Where `categoryMatch` is 1 for an exact match and 0 otherwise, and `dateProximity` is:

```
max(0, 1 − daysApart / 14)
```

### 13.5 Eligibility gates

A candidate is considered only if all of these are true:

- One report is LOST and the other is FOUND.
- They have different reporters.
- Both are OPEN and not soft-deleted.
- The text similarity is at least **0.20**.

The text gate is important. Category, location, and date are supporting signals; they cannot create a match on their own. Two unrelated “Electronics” reports from the same library in the same week should not be paired merely because their metadata agrees. The text must carry the pair over the line.

### 13.6 Presentation thresholds

| Threshold | Effect |
|---|---|
| Text similarity ≥ 0.20 | Candidate becomes eligible for scoring. |
| Score ≥ 35 | Candidate appears in the match panel (top five). |
| Score ≥ 60 | Candidate also produces an in-app alert for each author. |

These are heuristic product thresholds, not statistical confidence levels. They are adjustable after fixture review, but the meaning of the score stays the same.

Ties are resolved deterministically: higher score first, then candidate identifier ascending. The same inputs produce the same ordering.

### 13.7 Reasons

Reasons are emitted only for signals that are actually positive. A candidate does not receive “Same category” unless the categories match, and does not receive a date reason unless the dates are close. Typical reasons include:

- “Similar item description”
- “Same category”
- “Same campus location”
- “Reported two days apart”

The reason list is intentionally short. It is meant to help a user decide whether to open the candidate, not to simulate a full explanation of the model.

### 13.8 Worked arithmetic example

The following example uses assumed component values to show how the formula behaves. It is arithmetic, not a measured result.

Assume:

- text similarity = 0.52;
- category matches exactly;
- location similarity = 1.00;
- the reports are 2 days apart.

```
text       0.60 × 0.52 = 0.3120
category   0.20 × 1.00 = 0.2000
location   0.15 × 1.00 = 0.1500
date       0.05 × (1 − 2/14)
           = 0.05 × 0.8571 = 0.0429
                              ──────
total                        0.7049
score = round(70.49) = 70
```

A score of 70 would appear in the match panel and would also cross the alert threshold. The reasons would be: “Similar item description”, “Same category”, “Same campus location”, and “Reported two days apart”.

If the text similarity were 0.18, the pair would be ineligible no matter how well the category, location, and date agreed. That is the intended behaviour: metadata can support a textual lead, but it cannot replace one.

### 13.9 Snapshot and refresh lifecycle

Matching is not recomputed on every page view. It maintains a `match_candidates` snapshot keyed uniquely by the canonical LOST/FOUND pair.

```
item created/edited/status-changed/deleted
        │
        ▼
ItemChangedEvent (committed version)
        │
        ▼  after commit, new transaction
recompute the touched report
and every previously or newly related opposite report
        │
        ▼
replace complete candidate snapshots
        │
        ▼
MatchCandidatesChangedEvent
```

Key rules:

- A changed report can affect many users, so the refresh covers both the touched report and every opposite report that was or is related to it.
- Snapshots are replaced completely, not partially patched, so a removed candidate cannot linger.
- Candidates are purged or invalidated when either side becomes ineligible (reserved, resolved, closed, deleted, or no longer the right type).
- Results computed from stale source versions are discarded. A read must not serve obsolete eligibility.
- A failed computation does not roll back a valid report and does not remain broken forever; matching exposes a retry path.
- On application readiness, a bounded catch-up refresh recomputes open reports and publishes current snapshots, even if scores did not change, so a late consumer can backfill.

### 13.10 What matching deliberately does not do

- It does not use an LLM, image embeddings, OCR, or visual similarity.
- It does not learn from user clicks.
- It does not treat a high score as proof, approval, or a verified handover.
- It does not compare private evidence or contact data.
- It does not claim probability calibration.

These limits are stated openly because they are design choices, not hidden weaknesses. The matching problem is framed as ranked retrieval with explanations, which is exactly what the rest of the workflow can safely consume.

---

## 14. Security and privacy design

### 14.1 Authentication

- Registration and login are the only public credential endpoints.
- Passwords are hashed with BCrypt and never returned.
- Login returns a JWT signed with an HMAC key of at least 256 bits, Base64-encoded, supplied through the environment.
- The JWT carries the user ID and role, and expires after 24 hours by default.
- A retired demo key is explicitly rejected, so an old shared secret cannot be reused.
- Every authenticated request re-loads the user from the database, so a deleted or changed account does not retain stale authority.
- The frontend validates a stored token against `/auth/me` before enabling private queries; a stale local profile is not trusted.

### 14.2 Authorization

Authorization is enforced on the server, not just in the UI.

| Action | Who may do it |
|---|---|
| View public reports and public photos | Anyone. |
| Create a report | Any authenticated user. |
| Edit, change status, delete a report | Its reporter or an administrator. |
| View the editable snapshot | Its reporter or an administrator. |
| View claims on a report | Its reporter or an administrator. |
| Create a claim | Any authenticated user except the reporter, on an OPEN report. |
| Cancel a claim | Its claimant, while PENDING. |
| Approve or reject a claim | The target reporter or an administrator. |
| Release a reservation | The target reporter or an administrator. |
| View contact details | The claimant, the target reporter, or an administrator, only after approval/completion. |
| View ownership evidence | The claimant, the target reporter, or an administrator. |
| Generate a pickup code | The claimant, for an APPROVED FOUND claim. |
| Verify a pickup code | The target reporter or an administrator. |
| View or mark a notification | Its recipient only. |
| Admin endpoints | Users with the `ADMIN` role. |

Private route patterns are declared before broad public GET rules. This ordering prevents a private `/items/{id}/matches` or `/items/{id}/ownership-question` endpoint from accidentally becoming public because it matched a generic item route.

### 14.3 Data minimization and field visibility

The public item DTO is a deliberate whitelist. It contains:

- ID, type, title, description, category, location, date occurred, status;
- reporter ID and display name;
- created, updated, and resolved timestamps;
- version, photo URL, and pending-claim count.

It never contains:

- reporter email or phone;
- the ownership question or answer;
- claimant identity;
- claim messages;
- pickup codes or digests;
- internal storage names or secrets.

The claim DTO similarly contains lifecycle metadata and the claimant's display name for authorized viewers, but never the ownership answer or contact details. Contact details have their own endpoint, and evidence has its own endpoint, each with a separate authorization rule.

### 14.4 Private response handling

- Private API responses are marked `Cache-Control: no-store, private` and `Pragma: no-cache`.
- The contact endpoint and handover code issuance are explicitly non-cacheable.
- Public item lists, public item detail, and public photos are allowed to be cached; photo responses use a one-hour public cache with `X-Content-Type-Options: nosniff` and inline disposition.
- The frontend uses account-scoped query keys and clears the entire cache on login, logout, or token expiry, so one account's private data cannot appear for another.
- SSR creates a query client per request, preventing cross-request cache reuse.

### 14.5 Password and rate-limit controls

- BCrypt with per-password salt.
- Login limited to 10 attempts per 60 seconds per client address and path.
- Registration limited to 5 attempts per 300 seconds per client address and path.
- Rate-limit responses are JSON `429` with `Retry-After` and `no-store`.
- The limiter is in-memory, appropriate to a single demo instance; see limitations.

### 14.6 Photo security

- Only authenticated users may upload.
- Content type and extension must be JPEG, PNG, or WebP.
- Size is capped at 5 MiB; dimensions at 12,000 pixels per side.
- The server decodes the image, rejects unreadable files, and re-encodes the pixels, stripping EXIF, GPS, and embedded payloads.
- Stored filenames are random UUIDs; user filenames are discarded.
- A photo can be attached only by its uploader and to only one report.
- Public serving requires the photo to be attached to a visible, non-deleted report.
- Path resolution is checked against the storage root to prevent traversal.
- A per-user daily upload limit reduces abuse.
- Orphaned uploads are cleaned explicitly by an administrator.

### 14.7 Pickup-code security

- Six digits generated with a cryptographically secure random source.
- Ten-minute expiry.
- Five failed attempts, then LOCKED.
- 60-second reissue cooldown.
- Plaintext returned only in the issuance response; not stored, not logged, not placed in URLs or notifications.
- Stored value is a keyed HMAC digest, bound to the claim ID and generation, not an unsalted hash of six digits.
- Constant-time comparison.
- Regeneration invalidates the previous digest.
- Verification requires the target reporter or an administrator; the claimant cannot verify their own code.
- Completion and challenge update occur in one transaction under the shared lock order.
- A repeated successful verification by the authorized verifier returns the existing result without a second event.
- An invalid code cannot revive a released or completed challenge.

### 14.8 Concurrency and integrity

- Every mutation that touches reports or claims locks items in ascending ID order, then the claim, inside one transaction.
- The datasource runs at READ COMMITTED so a locking read after an earlier snapshot read sees the newest committed row, avoiding MariaDB's “record has changed since last read” failure.
- State is re-read under lock before a decision.
- Optimistic `version` fields reject stale edits with 409.
- Generated unique columns enforce at most one active reservation per target report and per linked lost report.
- Duplicate active claims by the same user on the same report are rejected under the item lock.
- Handover verification acquires the claim context before the challenge lock, matching the global lock order.
- Failed attempts are committed even when the request returns an error; failed completion cannot leave one report resolved and the other reserved.

### 14.9 Other application controls

- CORS is restricted to configured origins; credentials are allowed only for those origins.
- The API is stateless and CSRF is disabled because no cookie session is used; the JWT is sent in an `Authorization` header.
- JPA parameter binding prevents SQL injection in the search and lookup queries.
- Errors do not include stack traces or internal messages; unexpected failures return a generic 500.
- Server error messages are stable enough for the UI to map to specific guidance without exposing internals.
- Soft deletion preserves referential history while removing public visibility.
- The demo seed runs only under an explicit demo profile and only when the database has no users, so it cannot overwrite real records.

### 14.10 Threat summary

| Threat | Mitigation |
|---|---|
| Credential stuffing / brute force | BCrypt, per-IP rate limits, generic invalid-credentials response. |
| Token theft or replay | Short-lived signed JWT; user reloaded per request; expiry clears client state. |
| Enumeration of private data | Public DTO whitelist; 401 for private routes; 403/404 for unauthorized resources. |
| Claiming someone else's item | Private question, human review, reservation, and verified handover; matching never approves. |
| Reading another claimant's evidence | Evidence endpoint restricted to claimant, reporter, admin; no evidence in lists or notifications. |
| Contact scraping | Contact endpoint only after approval/completion, participants/admin only, no-store. |
| OTP brute force | Six digits, five attempts, ten-minute expiry, 60-second cooldown, keyed digest. |
| OTP reuse after regeneration | Generation-bound digest; previous digest replaced. |
| Forged handover by claimant | Claimant cannot verify; only reporter/admin can. |
| Race between approvals | Item locks in ascending order; re-read under lock; unique active reservation columns. |
| Race between verify and release | Shared lock order; one transition; single event. |
| Stale edit overwrite | Optimistic `version` checks and 409 responses. |
| Photo metadata leak | Decode and re-encode, strip metadata, random filenames. |
| Unattached photo access | Public serving requires attachment to a visible report. |
| Cache leakage across accounts | Account-scoped query keys; cache cleared on identity change; per-request SSR client. |
| Log or notification leak of secrets | Codes and evidence excluded from logs, events, notifications, and URLs. |
| SQL injection | JPA parameter binding. |
| Path traversal in uploads | Normalized path checked against storage root. |

---

## 15. Item and claim lifecycle

### 15.1 Item states

```
                         approve a FOUND claim
        OPEN ─────────────────────────────────────▶ RESERVED
         │  ▲                                           │
         │  │                                           │
         │  └──────────── release reservation ──────────┘
         │                                              │
         │                                      verified pickup
         │                                              │
         │                                              ▼
         ├──────── manual resolve ─────────────────▶ RESOLVED
         │                 ▲                            │
         │                 └── reopen (only if no ──────┘
         │                     verified handover)
         │
         └──────── close ──────────────────────────▶ CLOSED (terminal)

  soft delete (when not RESERVED) ──▶ hidden via deleted_at
```

| State | Meaning | Allowed next states |
|---|---|---|
| `OPEN` | Visible and accepting claims/responses. | `RESERVED` (approval), `RESOLVED` (manual or LOST response), `CLOSED`, soft delete. |
| `RESERVED` | An approved FOUND claim is awaiting pickup. Editing, status changes, and deletion are blocked until release. | `RESOLVED` (verified pickup), `OPEN` (release). |
| `RESOLVED` | The item has been returned or the report was manually resolved. | `OPEN` (reopen, unless a verified handover exists), `CLOSED`, soft delete. |
| `CLOSED` | Terminal withdrawn/closed report. | None; soft delete is still possible. |

Important details:

- `RESERVED` cannot be set directly through the status endpoint; only claim approval creates it.
- Approval sets `resolvedAt` to null on both the found report and any linked lost report.
- Verified pickup sets `RESOLVED` and `resolvedAt` on both, using the same timestamp.
- A manually resolved report can reopen; a report completed through verified handover cannot.
- A CLOSED report is final and cannot change status.

### 15.2 Claim states

```
                     approve FOUND claim
        PENDING ────────────────────────────▶ APPROVED
           │                                    │
           │                                    │ verified pickup
           │                                    ▼
           │                                COMPLETED
           │
           ├── reject ──────────────────────▶ REJECTED
           │
           ├── cancel ──────────────────────▶ CANCELLED
           │
           └── approve LOST response ───────▶ COMPLETED
                                              (no verified badge)

        APPROVED ── release ────────────────▶ CANCELLED
```

| State | Meaning | Who can cause it |
|---|---|---|
| `PENDING` | Awaiting the reporter's decision. | Created by a claimant. |
| `APPROVED` | A FOUND claim is reserved; contacts unlocked; pickup not yet verified. | Reporter/admin approval. |
| `REJECTED` | The reporter declined. History remains; a new claim may be sent while the report is open. | Reporter/admin. |
| `CANCELLED` | The claimant cancelled a pending claim, or the reporter/admin released an approved reservation. | Claimant (pending), reporter/admin (release). |
| `COMPLETED` | The claim is finished. For a FOUND claim, `handoverVerified` is true only if OTP verification completed it. For a LOST response, approval completes it directly without a verified badge. | Handover verification or LOST-response approval. |

### 15.3 Linked report semantics

A FOUND claim may link one LOST report owned by the claimant. The linked report must be LOST, OPEN, and owned by the claimant. One lost report can participate in at most one approved claim.

On approval of such a claim:

- both the found report and the linked lost report become RESERVED;
- both get `resolvedAt = null`;
- competing pending claims on either report are rejected;
- contact details unlock for the two participants.

On verified pickup:

- both reports become RESOLVED with the same timestamp;
- the claim becomes COMPLETED with `handoverVerified = true`;
- the verified badge is supported by the handover record.

On release:

- the claim becomes CANCELLED;
- both reserved reports return to OPEN;
- the release reason and time are recorded;
- contact details are denied again.

### 15.4 LOST responses are a separate flow

A response to a LOST report is a “I found this” message. It has no ownership question and no pickup code. Approval resolves that LOST report and completes the response directly. It never displays a verified-pickup badge, because no handover occurred in the FOUND-item sense. This keeps the two flows semantically distinct.

### 15.5 Transaction and locking rules

Every lifecycle mutation follows the same discipline:

1. Read a lightweight reference to the claim's target and linked report IDs.
2. Lock the involved items in ascending ID order.
3. Lock the claim.
4. Re-read current state under those locks.
5. Check actor permission and state preconditions.
6. Apply the transition.
7. Flush and publish committed-change events inside the transaction.

This order prevents deadlocks and makes competing approvals deterministic. The database's unique active-reservation columns provide a final backstop if application logic is bypassed.

### 15.6 Legacy data conversion

The schema migration preserves existing users, reports, and claims. Approved claims on already resolved items are converted to COMPLETED with a legacy completion time, but remain unverified because no handover record exists. Duplicate approved claims from an older schema are reduced to the oldest approved claim, with the others marked rejected, before the unique active-reservation constraints are applied. This makes the upgrade safe for existing data rather than requiring a destructive reset.

---

## 16. Testing and verification

### 16.1 Philosophy

The project treats verification as four questions:

1. **Does the lifecycle stay correct when two people act at once?**
2. **Can private data leak through an API, a cache, a log, a notification, or a URL?**
3. **Does the feature behave honestly in its empty, error, and legacy states?**
4. **Does a fresh checkout build and run with the documented commands?**

The tests are built around those questions rather than around line coverage.

### 16.2 Backend automated tests

The backend test suite runs against a real MariaDB test database (`lostfound_test`) with a clean Flyway schema, not an in-memory substitute. That matters because the lifecycle depends on real row locks and constraints.

**Lifecycle and workflow coverage** includes:

- FOUND approval reserves the item, rejects competing pending claims, and leaves `resolvedAt` null.
- LOST-response approval resolves the report and completes the response without a verified badge.
- Release reopens the reservation, records the reason, and blocks contact afterward.
- Two found reports cannot both reserve the same linked lost report.
- Two simultaneous approvals produce exactly one winner and one conflict.
- A question-bearing claim fails closed when no evidence policy is installed.
- Duplicate active claims are rejected.
- Manual resolution rejects pending claims.
- Reserved reports reject edits, deletion, and status changes.
- Soft deletion hides the report and rejects new claims.
- A stranger cannot decide or release.
- Stale edit versions are rejected; nullable fields can be cleared explicitly.

**HTTP, security, and privacy coverage** includes:

- Private routes return JSON 401 before broad public rules.
- Public item JSON never contains email, phone, ownership question, or answer.
- Malformed enums return 400; missing records return 404.
- Strangers cannot read claims or edit snapshots; administrators can.
- Contact details are participant-only after approval and are marked no-store.
- Photo upload requires authentication, rejects fake images, hides unattached files, serves attached files with `nosniff`, and prevents another user from attaching someone else's upload.
- Expired tokens are rejected.
- Identity responses are private and non-cacheable.
- Login issues a token and rejects bad credentials.
- Validation errors use the shared error shape.

**Feature verification** adds scenario families for:

- **Matching:** a strong paraphrase pair, a plausible pair, an unrelated same-category pair, different locations, date distance, repeated tokens, negative pairs above and below each threshold, stable ranking, pair uniqueness, same-author exclusion, terminal/deleted exclusion, private-proof isolation, refresh after edit/approval/release/resolution/deletion, restart catch-up, and a concurrent edit during recomputation.
- **Alerts:** one alert per author for a qualifying pair; none for low-score, same-author, stale-version, or terminal pairs; replay and concurrent delivery preserve uniqueness and read state; paging, read-one, read-all races, unread count, deletion, auth expiry, and two-account isolation.
- **Ownership:** public JSON and SSR reveal neither prompt nor answer; a signed-in claimant can fetch the prompt; only authorized readers can fetch the answer; missing, short, and oversized answers fail atomically with field-specific errors and no saved claim; hidden fields cannot change the stored prompt; question edits are frozen with pending claims; past snapshots survive later edits; rejected/cancelled resubmission works; two claimants' evidence stays separate.
- **Handover:** correct, wrong, expired, and locked codes; generation cooldown; replacement-code invalidation; leading zeros; unauthorized roles; no-code status reads; private response headers; five simultaneous wrong attempts; two simultaneous correct verifies; verify-versus-release; a failure during completion leaves neither report half-resolved; codes absent from logs and persisted plaintext; release reopens both reports.

### 16.3 Frontend verification

- `pnpm typecheck` checks the TypeScript contracts across routes, feature props, and API payloads.
- `pnpm build` verifies the production bundle and SSR entry.
- The UI distinguishes loading, empty, error, and success states; failed submissions keep input available for correction.
- Accessibility checks cover labelled controls, keyboard access to the notification popover, focus return, status announcements, and small-screen layout.
- Account switching is checked for cache clearing and absence of the previous user's badge, claims, contacts, or evidence.

### 16.4 Dependency and build verification

- The frontend install uses a frozen lockfile: `pnpm install --frozen-lockfile`.
- `pnpm audit` is part of the release check; critical advisories are treated as a gate.
- The backend uses the Maven wrapper and a pinned JDK: `./mvnw clean verify`.
- A fresh checkout must build without relying on a previously built JAR or an undocumented global JDK.
- Flyway runs against both a fresh database and an existing schema; Hibernate validates the result.

### 16.5 End-to-end rehearsal

A release rehearsal exercises the full narrative through the browser:

1. Log in as one account, browse, and report an item with a photo.
2. Edit the report and verify the version behavior.
3. Filter by location and status.
4. Submit a claim, cancel it, and submit again.
5. Approve a claim and confirm the reservation state and contact unlock.
6. Confirm that another account cannot see the first account's private data after switching.
7. Inspect public JSON and rendered HTML for email, phone, evidence, and secrets.
8. Run the matching panel and confirm reasons and ordering.
9. Confirm an alert appears within one polling interval and read state survives refresh.
10. Approve a FOUND claim and confirm “awaiting pickup”, not “resolved”.
11. Enter one wrong pickup code, then the correct code.
12. Confirm both reports are RESOLVED with equal timestamps and the verified badge appears.
13. Confirm the notification inbox and history reflect the completed handover.
14. Release a different reservation and confirm both reports reopen and contacts disappear.

The commands are listed in [Appendix B](#appendix-b--demo-accounts-and-commands).

### 16.6 What this document does not claim

No usage statistics, recovery rates, user counts, benchmark scores, or survey results are reported here. The system has not been instrumented for analytics, and no such measurements were invented for this document. The numbers that appear are design thresholds (weights, limits, expiries, attempts) and an arithmetic example.

---

## 17. Limitations and future scope

### 17.1 Current limitations

**Matching is text-first and deterministic.** It handles vocabulary overlap and metadata agreement, but it does not understand images, OCR, serial numbers, or deep paraphrase. A report with a very short description may fail the text gate even when a human would see the connection. The thresholds are heuristics, not calibrated probabilities.

**Evidence review is manual.** The system gives the reporter a private answer; it does not score truthfulness, verify identity, or detect copied answers. That is a deliberate choice, but it means a careless reporter can still approve the wrong person.

**One question per FOUND report.** The model supports a single prompt. Multiple questions, follow-ups, or structured evidence types are not part of this delivery.

**In-app alerts only.** Notifications are delivered inside the application. There is no email, SMS, push notification, or background job queue. A user who never opens the app will not see an alert elsewhere.

**Single-campus, single-instance operation.** The application assumes one campus context and one backend instance. Location is free text rather than a campus map or a controlled building registry. The rate limiter is in memory. Photo storage is a local directory.

**No institutional identity.** Any email address can register. There is no college SSO, student-ID verification, or staff directory integration. The trust boundary is the account, not the institution.

**No native mobile app.** The responsive web app works on phones and is designed for the in-person code exchange, but it is not a packaged iOS or Android application.

**No QR codes, maps, or pickup scheduling.** The pickup code is typed, not scanned. There is no map view, route, locker integration, or appointment calendar.

**No analytics or moderation tooling.** Administrators have counts and a report table, not trend analysis, abuse detection, or a full audit console.

**Accessibility and internationalization are not exhaustively audited.** The UI uses labelled controls, semantic elements, and accessible primitives, but a formal WCAG audit and full localization are outside this delivery.

### 17.2 Future scope

The following extensions would strengthen the product without changing its trust model:

1. **Richer similarity, still explainable.** Add image embeddings or OCR as additional signals, but keep the reason list and the rule that no single signal can approve a claim. A hybrid score could be evaluated against human judgements.
2. **Institutional identity.** Integrate campus SSO or student-ID verification to raise the cost of fake accounts while preserving privacy.
3. **Notification channels.** Add email digests, SMS, or web push for users who are not in the app, with the same deduplication and eligibility rules.
4. **QR-assisted pickup.** Let the claimant display a QR encoding the one-time code, while keeping the same HMAC digest, expiry, attempt limit, and two-device verification.
5. **Campus map and controlled locations.** Replace free-text locations with a building/room registry and a map view, improving matching and pickup logistics.
6. **Multi-campus tenancy.** Add an institution or campus scope to reports, users, and matching, with per-tenant administration.
7. **Moderation and abuse controls.** Add report flagging, rate limits per account, image moderation, and an administrator audit trail.
8. **Operational scale.** Move photo storage to object storage, rate limiting to a shared store, and event delivery to a durable queue; run multiple stateless API instances.
9. **Evaluation and analytics.** Instrument privacy-preserving aggregate metrics such as time-to-first-match and handover completion, without collecting private evidence or contact data.
10. **Formal accessibility and localization.** Complete a WCAG audit and support multiple languages and date formats.

### 17.3 Research questions

The project suggests several questions worth studying:

- How often do users trust an explained match over an unexplained one, and does explanation quality change claim behaviour?
- What question formats produce useful ownership evidence without being guessable or intrusive?
- Does a two-device pickup code reduce false completions compared with a simple “mark as returned” button?
- How should a similarity score be presented so users do not read it as a probability?
- What is the right balance between public searchability and private proof for sensitive categories such as identity documents and medication?

These are genuine open questions. The current system provides a stable platform for studying them.

---

## 18. Conclusion

CampusLost treats lost property as a trust problem, not just a listing problem. It gives a campus one searchable inventory, helps owners and finders discover each other with suggestions they can understand, protects the proof of ownership, keeps a human in the decision, and confirms the physical handover before declaring success.

The four defining capabilities fit together:

- **Explainable matching** turns two independent reports into a ranked, reasoned lead.
- **Automatic alerts** deliver strong leads without requiring constant searching.
- **Private ownership questions** test knowledge without publishing it.
- **OTP-verified handover** binds the digital record to a real exchange.

Around them sits a disciplined engineering foundation: a stateless API, JWT authentication, server-side authorization, BCrypt passwords, re-encoded photos, soft deletion, optimistic versions, pessimistic locks, database-enforced reservation uniqueness, versioned migrations, and a privacy-first DTO boundary.

The result is a system that is honest at every step. It does not claim a match is ownership. It does not claim approval is pickup. It does not claim a legacy completion is verified. It shows users what it knows, protects what it should not reveal, and records what actually happened.

For a campus, that means fewer items left in drawers and more returned to their owners. For an evaluation, it means a coherent, defensible piece of software with a clear problem, a clear design, a working implementation, and a credible path beyond the demo.

---

## Appendix A — Glossary

| Term | Meaning |
|---|---|
| **LOST report** | A report created by someone who lost an item. |
| **FOUND report** | A report created by someone who found an item. |
| **Claim** | A request to collect a FOUND item, or a “I found this” response to a LOST report. |
| **Response** | The LOST-report side of the claim model; approval resolves the report directly. |
| **Similarity score** | A 0–100 signal of textual and metadata similarity. Not a probability of ownership. |
| **Match candidate** | A canonical LOST/FOUND pair with a score and reasons. |
| **Ownership question** | A private prompt attached to a FOUND report. |
| **Evidence** | The prompt snapshot plus the claimant's private answer. |
| **Reservation** | The state created by approving a FOUND claim; the item is held but not returned. |
| **Handover** | The physical exchange confirmed by a pickup code. |
| **Pickup code / OTP** | A six-digit, short-lived, attempt-limited code generated by the claimant and verified by the finder. |
| **Verified handover** | A completed handover with a record, supporting the verified badge. |
| **Soft delete** | Hiding a report from public reads and matches while preserving its history. |
| **Optimistic locking** | Rejecting an update when the record's `version` has changed. |
| **Pessimistic locking** | Locking a row for the duration of a transaction to serialize competing changes. |
| **SSR** | Server-side rendering of the React application. |
| **JWT** | JSON Web Token used for stateless authentication. |
| **HMAC** | Keyed message authentication code used to protect pickup-code digests. |

---

## Appendix B — Demo accounts and commands

### B.1 Local demo accounts

These accounts exist only when the explicit demo profile is active and the local demo seed flag is enabled. The seed runs only when the database has no users, so it cannot overwrite real records.

| Role | Email | Password |
|---|---|---|
| Admin | `admin@lostfound.dev` | `admin123` |
| User | `alice@demo.dev` | `demo123` |
| User | `bob@demo.dev` | `demo123` |
| User | `carol@demo.dev` | `demo123` |
| User | `dave@demo.dev` | `demo123` |

Demo credentials are shown in the UI only when `VITE_DEMO_MODE=true`, which the local bootstrap script writes. They are not part of a production configuration.

### B.2 Prerequisites

- Java 25, installed through the checked bootstrap script rather than the system JDK.
- Node.js 22+ and pnpm 12.
- MariaDB 11+ running locally.

### B.3 First-time setup

```bash
scripts/bootstrap-java.sh          # verifies the checksum and installs the pinned JDK
scripts/setup-db.sh                # creates lostfound and lostfound_test plus the app user
scripts/bootstrap-local.sh         # writes local secrets and frontend demo settings
```

The local bootstrap writes `.secrets/backend.properties` with the JWT and handover HMAC secrets, mode 600, outside Git. The backend refuses to start without a valid JWT secret, and rejects the retired demo key.

### B.4 Run the application

```bash
# Terminal 1 — API on http://127.0.0.1:8080 with demo data
scripts/dev-backend.sh

# Terminal 2 — frontend on http://localhost:3000
scripts/dev-frontend.sh
```

The frontend proxies `/api` to the backend. The demo seed requires both the `demo` profile and `app.demo.seed=true`; a normal run starts empty.

### B.5 Build and test

```bash
# Backend: unit and integration tests against lostfound_test, then package
source scripts/java-env.sh
cd backend && ./mvnw clean verify

# Frontend: exact, audited dependencies
cd frontend
pnpm install --frozen-lockfile
pnpm typecheck
pnpm build
pnpm audit
```

### B.6 Reset local demo data

```bash
sudo mariadb -e "DROP DATABASE lostfound; CREATE DATABASE lostfound CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
rm -rf backend/uploads/*
scripts/dev-backend.sh
```

Flyway recreates the schema and the demo profile reseeds the reports and claims.

### B.7 Configuration reference

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | local `lostfound` | JDBC URL. |
| `DB_USERNAME` / `DB_PASSWORD` | local app user | Database credentials. |
| `JWT_SECRET` | required, no default | Base64 HMAC key of at least 32 bytes. |
| `APP_HANDOVER_HMAC_SECRET` | required for handover | Key used for pickup-code digests. |
| `APP_PHOTOS_DIR` | `backend/uploads` | Local photo storage; git-ignored. |
| `APP_CORS_ALLOWED_ORIGINS` | localhost and portless origin | Allowed browser origins. |
| `SERVER_PORT` | `8080` | API port. |
| `VITE_DEMO_MODE` | unset | Shows demo credentials in the UI. |
| `APP_LOGIN_RATE_LIMIT` | `10` per 60 seconds | Login attempt limit. |
| `APP_REGISTER_RATE_LIMIT` | `5` per 300 seconds | Registration attempt limit. |

### B.8 Presentation talking points

For a short deck, the strongest sequence is:

1. **The gap** — campus lost property is fragmented, vocabulary-dependent, unsafe to prove publicly, and unverified at handover.
2. **The insight** — a lead is not proof, and approval is not pickup.
3. **The chain** — report → suggest → prove → decide → verify pickup → resolve.
4. **The four features** — explainable matching, automatic alerts, private questions, OTP handover.
5. **The engineering** — deterministic matcher, event-driven snapshots, fail-closed evidence, locked lifecycle, keyed OTP.
6. **The privacy boundary** — what is public, what is participant-only, what is never stored in plaintext.
7. **The demonstration** — the two-account bottle journey, including one wrong code and one correct code.
8. **The limits and the path** — text-first matching, manual review, in-app alerts, single campus; richer similarity, SSO, push, QR, multi-campus next.

The strongest single sentence for the report or the closing slide is:

> CampusLost does not guess who owns an item. It explains why two reports might be the same, lets a human review private evidence, and requires a two-device code before it records that the item was returned.
