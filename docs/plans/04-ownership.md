# Private ownership questions and evidence

Owner: `feature/ownership`, from the frozen fixes SHA. Read [00-overview.md](00-overview.md). Own `com.lostfound.ownership`, `frontend/src/features/ownership`, V20 only if needed, and evidence tests. Shared fields, form payloads, and the ItemEvidencePolicy/ClaimEvidencePolicy hooks already exist on fixed main.

## Goal and experience

A finder asks about a distinguishing detail that was deliberately withheld from the public description. A signed-in claimant sees that question and submits an answer privately with the claim. The finder reviews the answer before approval. The product explains that a suggestion is a lead and proof still needs human review.

Use one question per FOUND report for tomorrow. Examples: "What is engraved under the lid?" or "Describe the wallpaper without unlocking the phone." Never request a device passcode, full identity document, payment information, or other sensitive credential. The question itself should not disclose its answer.

## Contracts and implementation boundary

Implement the prepared controlled components `OwnershipQuestionField`, `OwnershipAnswerField`, and `ClaimEvidence` with the props defined in the overview. Use feature-local styles/types/helpers and existing form controls. The parent forms already send optional ownershipQuestion/ownershipAnswer; wire validation through their established callback/validity contract. A failed submit keeps the answer available for correction.

The reporter creates/edits the prompt through the existing item input, which fixes persists in the shared private field. Require a trimmed prompt of 10-200 characters for new FOUND reports when this adapter is installed. Enforce that server-side through ItemEvidencePolicy from the overview. Existing no-question reports stay usable and show "No question supplied"; LOST reports do not carry this field. Pending claims freeze question edits so evidence stays meaningful.

Own `GET /items/{id}/ownership-question`: authenticated, accessible only for a visible FOUND item, returning `{itemId,question:string|null,required:boolean}`. Claimants see the prompt, not any previous answers. Reporter/admin can read it for review even when reserved. The public item DTO never gains the prompt. Return unavailable/closed state clearly rather than offering a claim on a terminal report.

Supply ClaimEvidencePolicy.prepare. Under ClaimService's existing transaction and item lock, validate the current question and answer, require 10-2000 trimmed characters when a question exists, and return its exact prompt snapshot plus answer. Reject supplied answers for LOST responses. Snapshot and claim creation commit together. Never rely only on client length validation or fetch the current prompt after the claim has already been saved.

Own `GET /claims/{id}/evidence`, returning `{claimId,question,answer}` only to that claimant, target reporter, or admin. Derive identity from authentication. Scope lists/history reads through the same rule, set no-store, and exclude evidence from logs, events, notifications, matching, public DTOs, and other claimants' screens.

Review stays manual. Avoid automatic approval, displayed answer hashes, or a "verified owner" badge based on typing an answer. Approval reserves the item through the core workflow; OTP separately confirms collection. Rejected/cancelled submissions remain private history and do not prevent a new attempt allowed by the core service.

## Acceptance and verification

- Create FOUND with a prompt; public list/detail/SSR/notification JSON reveals neither prompt nor answer. Signed-in claimant can fetch the prompt; only authorized evidence readers can fetch the answer. LOST response behavior remains intact.
- Missing/short/oversized answers fail atomically with field-specific 400 responses and no saved claim. Failed client submission retains input. Hidden fields cannot be spoofed to change a stored prompt or reviewer.
- Editing with pending claims is rejected; past claim snapshots do not change when a later edit is permitted. Legacy reports display the exception honestly. Test resubmission after rejection/cancellation.
- Demonstrate two claimants: their answers remain separate; one cannot query the other's evidence. Switch accounts and inspect cache/SSR privacy.
- Backend tests exercise the real adapter through ClaimService, including question-update races. Frontend typecheck/build and backend clean verify pass without matching/alerts/handover.

Hand off the reviewer flow and privacy tests. Multiple questions, automated identity scoring, and institutional identity verification are deferred.
