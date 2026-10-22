# Explainable lost/found matching

Owner: `feature/matching`, from the frozen fixes SHA. Read [00-overview.md](00-overview.md). Own `com.lostfound.matching`, `frontend/src/features/matching`, V10 if persistence needs a migration, and matching tests. Leave core services/routes/models/types alone.

## Goal and experience

After a report is posted, its author sees up to five useful opposite-type suggestions. Each has a photo, location/date, and plain reasons such as "Similar item description", "Same campus location", and "Reported two days apart". Call the number a similarity score, not an ownership probability. A score cannot approve a claim.

On a LOST report, a suggestion opens the FOUND item with `lostReportId` preselected for its owner's claim. On a FOUND report, suggestions let the finder inspect LOST reports; they do not let the finder claim an item for someone else. Empty/loading/error states are part of the feature.

## Algorithm and contracts

Use local deterministic text retrieval so the demo works without an external API, model download, or key. Normalize case/punctuation, remove common stop words, and compare title/description tokens with title weighted more heavily. Choose an understandable token/cosine implementation and document the formula. Exclude private questions, answers, contacts, OTPs, and image metadata from matching.

Use this initial weighted score, rounded to an integer 0-100: 60% text, 20% exact category, 15% normalized location similarity, 5% date proximity within fourteen days. Require text similarity at least 0.20 before a candidate is eligible. Emit explanations only for actual positive signals. Return the top five at score >=35; alerts use >=60. These are heuristic thresholds, adjustable by the lead after fixture review, never confidence claims. Reject same-reporter pairs and non-OPEN/deleted reports. Category/date/location alone cannot produce a match. Stable tie-break is score descending then candidate ID ascending.

Own `GET /items/{id}/matches`, reporter/admin only, returning `{itemId, matches:[{item:PublicItemSummary, lostItemId, foundItemId, score, reasons:string[]}]}`. Use the fixes-owned public summary mapper and actor-scoped query key. No candidates is 200 with an empty array; inaccessible source is 404/403 per shared auth rules.

Own a persistent `match_candidates` snapshot keyed uniquely by lost/found IDs, with both source versions, score, reasons, and updated time. An ItemChangedEvent listener recomputes relevant candidates after commit in a separate transaction. A changed report can affect many users: refresh the touched report and every previously/newly related opposite report, with complete replacement snapshots, not only its own list. Purge/invalidate candidates when either side becomes ineligible. Discard results computed from stale source versions; a read must not serve obsolete eligibility.

After each successful refresh publish `MatchCandidatesChangedEvent` in that transaction using the overview's immutable pair payload. Alerts consumes it later. Keep records canonical LOST/FOUND and deduplicate pairs. On application readiness perform a bounded catch-up refresh of OPEN reports and publish current snapshots, even if scores did not change, so alerts installed later can backfill. Keep an explicit refresh/retry path for failed after-commit jobs; a failed match computation must not roll back a valid report or silently remain broken forever.

Implement `MatchPanel({item})` at the prepared UI entrypoint. Namespace all new types, API helpers, and styles within the owned directory. Match reads and listeners must remain usable without the alerts branch.

## Acceptance and verification

- Fixtures include a strong paraphrase/overlap pair, a plausible pair, an unrelated same-category pair, different locations, date distance, and repeated tokens. Explain exactly why ranking changes. Include at least one negative pair above/below each threshold.
- Same-author, same-type, RESERVED/RESOLVED/CLOSED/deleted pairs never appear or produce eligible events. Private proof terms cannot alter scores. Refresh after edit, approval, release, resolution, and deletion updates both authors' lists.
- Test stable ranking, pair uniqueness, restart catch-up, and a concurrent edit during recomputation. Event payloads use committed versions and contain no private fields.
- With only this branch on fixed main, frontend typecheck/build and backend clean verify pass. Exercise the API as owner, stranger, admin, and anonymous visitor. UI links select the correct lostReportId and preserve it through login.

Return representative scores/reasons and the catch-up/retry procedure with the usual branch/SHA/test handoff. Leave AI/image matching and large-scale search indexes out of this delivery.
