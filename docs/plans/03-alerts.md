# Automatic in-app match alerts

Owner: `feature/alerts`, from the frozen fixes SHA. Read [00-overview.md](00-overview.md). Own `com.lostfound.notifications`, `frontend/src/features/notifications`, V40, and notification tests. This branch merges last but must compile without matching, ownership, or handover implementations.

## Goal and experience

A report's author learns about a strong likely match without repeatedly searching. The header shows an unread badge. Opening it displays a small inbox with useful report titles, actual match reasons, time, and one clear destination. Read state survives refresh. "No new matches" differs from "Could not load alerts". Notifications never imply an item was proven or returned.

## Model, producer boundary, and API

Own `notifications`: id, recipient user, type, dedupeKey, lostItemId/foundItemId or claimId, source item versions, score/reasons snapshot, active, createdAt, readAt. Unique `(recipient_id,dedupe_key)` is required. Types are `MATCH_FOUND`, `CLAIM_APPROVED`, `CLAIM_REJECTED`, and `HANDOVER_COMPLETED`. Status alerts are small extensions of the same mechanism; match alerts are the essential acceptance gate.

Subscribe to the shared MatchCandidatesChangedEvent after commit; start a new transaction for delivery. For eligible score >=60 notify each report's author once, using `match:<lostId>:<foundId>` per recipient. Replayed events, concurrent events, score changes, and application restarts must not generate duplicates or reset readAt. Reconcile existing rows for the event's source: deactivate pairs absent from its current snapshot or below threshold, and update surviving reasons/versions without resetting readAt. Match generation can send snapshots for either source, so the same pair may arrive twice. Confirm both report versions/statuses before delivery; stale snapshots are ignored. Recompute/catch-up belongs to matching, not this branch.

Subscribe to ClaimChangedEvent for optional status alerts: approval/rejection to claimant, successful handover to both participants; dedupe by claim/status. HANDOVER_COMPLETED requires `handoverVerified=true`, not merely COMPLETED. Do not put answers, contact data, or OTPs in messages/events/links. Status updates reflect committed transitions only.

- `GET /me/notifications?limit=20&beforeId=...` returns `{notifications:[{id,type,title,body,itemId,claimId?,lostReportId?,createdAt,readAt}],unreadCount,nextBeforeId}`. Maximum limit 50. Sort newest first. Construct navigation from IDs and fixed routes, never caller-supplied arbitrary URLs.
- `PATCH /me/notifications/{id}/read` is idempotent and returns the authorized row; another user's ID returns 404.
- `POST /me/notifications/read-all` returns `{unreadCount:0}` after marking this user's current rows read. Notifications arriving afterward remain unread.

Match notifications whose reports are no longer OPEN/publicly visible are excluded from the active list and unread count. Historical claim notifications may remain but lead to authorized history. Apply eligibility on read as well as on events, so a delayed listener cannot expose obsolete matches. Scope every DB operation to the authenticated recipient; never accept recipientId in the request.

Implement the prepared `NotificationBell()`. Poll every 20 seconds while signed in and visible, refresh on focus, and stop on logout/background. Use shared account query keys and cache clearing. Navigation from a LOST author's match alert selects the FOUND candidate plus that author's lostReportId. Finder notifications open their own FOUND report/match panel. Show the badge as zero/hidden while loading a new account, never the prior account's count.

## Acceptance and verification

- Unit/integration tests publish the contract event directly; that is a test fixture, not a production matching stub. With no producer installed the real inbox is simply empty.
- One qualifying pair produces one alert per author; low-score, same-author, stale-version, and terminal-report pairs produce none. Replays/concurrent delivery preserve uniqueness and read state.
- Test paging, read-one, read-all races, unread count, status changes, deletion, auth expiry, and two-account isolation. Another account cannot list or mark the first account's alerts.
- After integration, reporting/editing creates real matching events; an alert appears within one polling interval. Approval suppresses the active match alert; verified completion produces accurate status history if included.
- Backend clean verify and frontend build/typecheck pass independently. T3-only interaction verifies keyboard access, focus return from the popover, small-screen layout, refresh, and notification links.

Hand off the migration, dedupe rules, observed delivery delay, and tests. Email, sockets, push permissions, and background job infrastructure are outside tonight's scope.
