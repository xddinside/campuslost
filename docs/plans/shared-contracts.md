# Shared contracts

Authoritative reference for all builders. Read [00-overview.md](00-overview.md) for ownership, merge order, and gates, then this file before the assigned brief. The fixes agent installs these contracts before the four feature branches fork. Changes require the integrating lead to update this reference and notify every affected builder.

## Data and lifecycle

Keep MariaDB/JPA and existing numeric IDs. Fixes adds `Item.version`, `updatedAt`, `deletedAt`, optional `photoId`, and optional private `ownershipQuestion` with maximum 200 characters. Add `Claim.version`, optional `lostReport` relation, `ownershipQuestionSnapshot`, `ownershipAnswer`, `decidedAt`, and `completedAt`. Widen status columns to 32 characters. Never serialize entities directly.

Item states: `OPEN`, `RESERVED`, `RESOLVED`, `CLOSED`. Claim states: `PENDING`, `APPROVED`, `REJECTED`, `CANCELLED`, `COMPLETED`.

- New claims require an OPEN target, another reporter, and no existing active claim by that user on that target. FOUND targets are ownership claims. LOST targets retain the existing "I found this" response, without ownership questions or OTP. Approving a LOST response resolves that report and completes that response directly; it never displays a verified-pickup badge.
- A FOUND claim may link `lostReportId`. That report must be LOST, OPEN, and owned by the claimant. Approval reserves both reports and rejects competing pending claims on either report. One report can participate in at most one APPROVED claim.
- FOUND approval means APPROVED plus RESERVED, with `resolvedAt = null`. Successful OTP verification means COMPLETED plus RESOLVED for target and linked lost report, with identical completion timestamps. Approval itself never means collection.
- Pending claims may be cancelled by their claimant. Rejected/cancelled history remains. Resubmission creates a new claim; UI checks active claims rather than blocking on any historical claim.
- A finder/admin may release an APPROVED reservation with a reason. The claim becomes CANCELLED and both reserved reports reopen. Closing, editing, manually resolving, or deleting a reserved report returns 409 until release. Terminal claims cannot be approved again.
- Manual resolution of an OPEN report remains available; it rejects pending claims and carries no verified badge. Reopening a manually resolved report clears `resolvedAt`; reports in a completed handover cannot reopen. CLOSED is terminal. Deletion becomes soft deletion and hides the report from public reads and matches while preserving claim history.
- Existing APPROVED claims on already resolved items migrate to COMPLETED with legacy completion time. They remain unverified: only a completed handover record supports a verified badge. Preserve existing user/item data; demo seed changes must be explicit and repeatable.

Fixes supplies a shared `ClaimWorkflow` for approval, cancellation, release, and verified completion. Every mutation touching reports/claims takes pessimistic item locks in ascending ID order, then claim locks, inside one transaction; creation and duplicate checks use the same item lock. Re-read state under lock. Linked-report competition and deletion follow this order too. Optimistic versions reject stale edits with 409. Handover calls workflow operations inside its verification transaction, never through an HTTP call back into the app.

## HTTP and privacy contracts

All paths are under `/api`. IDs are numbers, timestamps ISO-8601 UTC, request bodies JSON except photo upload. Error shape is `{code, message, fieldErrors?}`; `fieldErrors` maps field names to messages. Use 400 for malformed input, 401 for absent/expired credentials, 403 for forbidden actions, 404 for absent/inaccessible resources, 409 for state/version conflicts, 413 for size limits, 429 for attempt limits, and 503 for an unavailable required feature. Preserve `message` compatibility.

Existing item create/edit input gains optional `ownershipQuestion` and `photoId`; edits use `PATCH /items/{id}` with `version` and changed fields. Use explicit null to remove a photo/question, and omission to preserve it. `GET /items/{id}/edit` is reporter/admin only and returns the private editable fields with version. Existing claim input becomes `{message, lostReportId?, ownershipAnswer?}`. Existing claim decisions remain `{status: APPROVED|REJECTED}`. ClaimResponse gains `itemType`, `reporterId`, `lostReportId`, `version`, `decidedAt`, and `completedAt`; it never contains private evidence. Fixes publishes complete TypeScript types for these shapes before the fork.

Public item DTOs omit email, phone, ownership question/answer, and claimant identity. Include `version`, `updatedAt`, `photoUrl`, and ordinary report metadata. Claim responses expose the claimant name and lifecycle metadata only to the claimant, report reviewer, or admin. Ownership feature owns the separate authorized evidence reads. Fixes adds `GET /claims/{id}/contact` for participants/admin only after approval or completion, returning `{claimId,reporter:{name,email,phone},claimant:{name,email,phone}}`; no contact data enters public list caches. Existing email/phone UI must consume that endpoint or disappear.

Question prompts are visible only to signed-in users through the ownership endpoint, with stronger ownership checks on answers. The report author receives the current prompt through an owner-only edit DTO. Found-item copy asks authors to withhold identifying details from the public description. New FOUND reports require a question once the ownership feature is installed; legacy reports may proceed without one with an honest "No question supplied" review state.

Photo upload is fixes-owned: `POST /photos` multipart `file`, authenticated, returns `{id,url}` with an opaque ID. One JPEG/PNG/WebP up to 5 MiB per report. Check decoded content, re-encode to remove metadata, use random server filenames, and keep storage outside Git. Only the uploader may attach an unattached photo. Public serving exposes only photos attached to publicly visible reports. Photo deletion/replacement must not break another report or leak orphan files.

## Shared extension contracts

Fixes defines immutable Spring events in `com.lostfound.contract`, published within the writing transaction. Feature consumers act after commit and start a new transaction for their writes. Payloads contain IDs/versions, never managed entities or secrets.

- `ItemChangedEvent(itemId, version, kind)` where kind is CREATED, UPDATED, STATUS_CHANGED, or DELETED. Publish for both reports when a linked workflow changes them.
- `ClaimChangedEvent(claimId, itemId, itemType, lostReportId, claimantId, reporterId, status, handoverVerified)`. Only real committed transitions emit events. `handoverVerified` is true only for the workflow's successful OTP completion; LOST responses and legacy resolution never set it.
- `MatchCandidatesChangedEvent(itemId, itemVersion, candidates)` contains the current eligible matches for that report, including an empty list when none remain. Each candidate has `lostItemId`, `foundItemId`, `lostReporterId`, `foundReporterId`, `score`, `reasons`, and both item versions. Matching owns producing it; alerts owns consuming it. Each pair is canonical LOST then FOUND. Consumers reject a stale source version even for an empty snapshot.

`ClaimEvidencePolicy` is an optional injected adapter with `prepare(item, claimant, request)` returning `{questionSnapshot, answer}` or a validation error. ClaimService calls it and saves the returned evidence atomically with the claim. With no adapter installed, legacy/no-question claims work; a question-bearing report fails closed with a clear unavailable error. Ownership supplies the adapter. No feature replaces ClaimService.

`ItemEvidencePolicy.validate(actor, existingItemOrNull, mergedInput)` is the companion optional adapter called by ItemService inside its create/edit transaction before saving. Ownership supplies it to require questions on new FOUND reports and reject questions on LOST reports. Core edit policy already prevents question changes with pending claims. Fixes persists the optional input but installs no pretend proof validator.

`ClaimWorkflow` gives handover a locked claim context and operations to complete or release that reservation. Handover must acquire that context before its challenge-row lock. OTP verification and workflow completion share one transaction. No ordinary status/decision endpoint can complete a FOUND handover.

Frontend fixed-main integration imports these named entrypoints from `index.tsx` in each owner's frontend directory specified in the overview, which their owners replace. Fixes puts their shared prop types and the parent form validity type in `frontend/src/lib/feature-contracts.ts`:

- Matching: `MatchPanel({item})` on owner/admin item details. Links to a FOUND candidate carry optional `lostReportId` in validated route search and preselect it in the claim form.
- Ownership: `OwnershipQuestionField({type,isNew,value,onChange,error,onValidityChange})` on create/edit; `OwnershipAnswerField({itemId,value,onChange,error,onValidityChange})` on a FOUND claim form; `ClaimEvidence({claimId})` in reviewer/claimant history. Parent forms pass `ownershipQuestion`/`ownershipAnswer` through the fixed inputs. `onValidityChange({valid,message?})` reports loading/invalid fields to the parent; a parent cannot submit while required question/answer state is unknown or invalid. Empty foundation entrypoints report valid because that feature is absent, while the server still fails closed for question-bearing claims.
- Handover: `HandoverPanel({claim,onChanged})` for an APPROVED or COMPLETED FOUND claim, rendered for both claimant and reviewer.
- Alerts: `NotificationBell()` in Header; use a popover/drawer, so no new route registration is needed.

Fixes reserves `app.handover.hmac-secret` bound to `APP_HANDOVER_HMAC_SECRET`, with no committed value. The handover module validates and consumes it when installed. Setup provisions it outside Git alongside the JWT key. Feature-owned records and API types stay within their own Java/TypeScript directories; only the shared contracts above live in the fixed-main common files.

Fixes establishes actor-scoped query keys, for example `['account', userId, 'claims']`, `['account', userId, 'item', itemId, 'matches']`, and `['account', userId, 'notifications']`. Public items have separate keys. All identity changes cancel requests and clear the cache before switching accounts. A shared `invalidateWorkflow(queryClient)` refreshes public reports, item details, account reports/claims/evidence/matches, admin counts, and notifications after a mutation. Feature code uses these helpers, not its own QueryClient. Private queries wait for validated authentication. SSR creates one QueryClient per request.
