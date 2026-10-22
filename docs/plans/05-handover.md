# OTP-verified handover

Owner: `feature/handover`, from the frozen fixes SHA. Read [00-overview.md](00-overview.md). Own `com.lostfound.handover`, `frontend/src/features/handover`, V30, and handover tests. Use the shared ClaimWorkflow; do not rewrite claim approval or core status endpoints.

## Goal and experience

Approval says "Approved, awaiting pickup" and leaves both linked reports RESERVED. The claimant generates a pickup code on their own device and shows it in person. The finder enters that code on their device. Only a successful verification records pickup and resolves the pair. The finder never fetches the code from the server. Admin assistance follows the same code requirement; there is no bypass button.

Wrong/expired/locked codes have clear feedback. The UI shows expiry, attempts remaining for the verifier, and how to regenerate or release a reservation. Codes should never appear in URLs, notifications, analytics, or logs.

## Model and API

Own `handover_challenges`: id, claimId unique, keyed code digest, expiresAt, failedAttempts, nextIssueAt, verifiedAt, generation/version. Keep one current challenge per claim; regeneration replaces and invalidates the previous digest. Record completion time/verifier, and keep the core Claim.completedAt consistent. The verified badge depends on a verified handover row, including for legacy COMPLETED claims.

- `POST /claims/{id}/handover/code`, claimant only, APPROVED FOUND claims only. Returns `{code,expiresAt}` with no-store headers. Use cryptographically random six digits valid for ten minutes, five failed verification attempts, and a 60-second issuance cooldown. Return the plaintext only in this issuance response and keep it in component memory; reload requires regeneration after cooldown. Hash with a dedicated environment-configured server HMAC key, never a plain six-digit unsalted hash; compare in constant time. Missing HMAC configuration fails startup or issuance clearly and safely. Fixes reserves the environment variable/config binding before the fork; actual secret provisioning remains local.
- `GET /claims/{id}/handover`, participants/admin only, returns `{claimId,state:NOT_ISSUED|READY|EXPIRED|LOCKED|COMPLETED,expiresAt,attemptsRemaining,verifiedAt}`. It never returns code or digest. Legacy completed claims may have `verifiedAt:null`; label them as resolved without pickup verification.
- `POST /claims/{id}/handover/verify`, target reporter/admin only, body `{code}`. Returns the updated authorized claim response plus `verifiedAt`. Claimants cannot verify themselves. Wrong/expired code is a controlled 400; exhausted attempts/cooldown is 429 with Retry-After; wrong lifecycle is 409.
- `POST /claims/{id}/handover/release`, target reporter/admin only, body `{reason}`. Uses the core release operation to cancel the approved reservation and reopen both reports; invalidate any challenge. This provides a recovery path after a mistaken approval/no-show.

Acquire ClaimWorkflow's locked context first, then lock the challenge, inside one transaction. Verification checks eligibility/expiry/attempts under lock. Successful verification updates challenge and completes both reports atomically. Failed attempts must persist even if returning an error: do not accidentally roll back the increment by throwing a transactional exception. Regeneration and release follow the same lock order. Concurrent verification can produce only one transition/event; a repeated successful request by the authorized verifier returns the existing completed result without another event. An invalid code cannot revive a released/completed challenge.

HMAC key loading is isolated to this module using the fixed-main property. Bind each digest to claim ID and generation as well as the code. Use injected time/randomness for tests; never add a production fixed OTP or a route that reads stored plaintext. No automatic timer may resolve a report.

## UI and verification

Replace the prepared `HandoverPanel({claim,onChanged})`. Claimants get Generate code and a readable expiry; finder/admin get a labelled numeric input, verify, and a secondary release action with confirmation/reason. Completed claims show the actual verified timestamp. Invoke shared workflow invalidation after generation/release/completion so personal reports, matching, admin counts, and alerts converge.

- Test correct/wrong/expired/locked codes, generation cooldown, replacement-code invalidation, leading zeros, unauthorized roles, no-code status reads, and private response headers. Check logs and persisted rows for plaintext.
- Test five simultaneous wrong attempts, two simultaneous correct verifies, verify-versus-release, and a failure during completion. Failed attempts persist, while failed completion never leaves one report resolved and the other reserved.
- Through real claim approval, demonstrate RESERVED with null resolvedAt, then one wrong code and a correct one, then COMPLETED with both reports RESOLVED and equal timestamps. Direct status/claim-decision requests cannot perform that completion. Release reopens both reports and allows matching again.
- Run backend clean verify and frontend build/typecheck independently. At final integration confirm claim/evidence review precedes pickup and notifications never contain the code.

Hand off the required property name, local secret setup, race-test evidence, and the recovery flow. SMS/email delivery, QR pickup codes, and scheduled pickup appointments are outside this delivery.
