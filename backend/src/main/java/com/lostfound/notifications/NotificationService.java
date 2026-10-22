package com.lostfound.notifications;

import com.lostfound.contract.ClaimChangedEvent;
import com.lostfound.contract.MatchCandidate;
import com.lostfound.contract.MatchCandidatesChangedEvent;
import com.lostfound.exception.ApiException;
import com.lostfound.model.Claim;
import com.lostfound.model.ClaimStatus;
import com.lostfound.model.Item;
import com.lostfound.model.ItemStatus;
import com.lostfound.model.ItemType;
import com.lostfound.model.User;
import com.lostfound.repository.ClaimRepository;
import com.lostfound.repository.ItemRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Delivers contract events into the notifications table and serves the
 * authenticated inbox. Delivery always starts a fresh transaction after the
 * writing transaction commits; reads always re-check report visibility.
 */
@Service
public class NotificationService {

    public static final int MIN_MATCH_SCORE = 60;
    public static final int MAX_PAGE_SIZE = 50;
    public static final int DEFAULT_PAGE_SIZE = 20;

    private static final int TITLE_LIMIT = 200;
    private static final int BODY_LIMIT = 500;

    private final NotificationRepository notifications;
    private final ItemRepository items;
    private final ClaimRepository claims;
    private final JdbcTemplate jdbc;

    public NotificationService(NotificationRepository notifications,
                               ItemRepository items,
                               ClaimRepository claims,
                               JdbcTemplate jdbc) {
        this.notifications = notifications;
        this.items = items;
        this.claims = claims;
        this.jdbc = jdbc;
    }

    /** Handles a full candidate snapshot for one source report. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleMatchSnapshot(MatchCandidatesChangedEvent event) {
        if (event == null || event.itemId() == null) {
            return;
        }
        Item source = items.findById(event.itemId()).orElse(null);
        if (source == null || source.isDeleted() || source.getVersion() != event.itemVersion()) {
            // A stale snapshot, including a stale empty one, must not change rows.
            return;
        }

        Map<Long, Set<String>> activePairsByRecipient = new HashMap<>();
        List<AcceptedPair> accepted = new ArrayList<>();
        List<MatchCandidate> candidates = event.candidates() == null ? List.of() : event.candidates();

        for (MatchCandidate candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            boolean touchesSource = Objects.equals(candidate.lostItemId(), event.itemId())
                    || Objects.equals(candidate.foundItemId(), event.itemId());
            if (!touchesSource || candidate.score() < MIN_MATCH_SCORE) {
                continue;
            }

            Item lost = items.findById(candidate.lostItemId()).orElse(null);
            Item found = items.findById(candidate.foundItemId()).orElse(null);
            if (!eligible(lost, candidate.lostItemVersion())
                    || !eligible(found, candidate.foundItemVersion())) {
                continue;
            }

            Long lostAuthor = lost.getReporter().getId();
            Long foundAuthor = found.getReporter().getId();
            // Payload reporter IDs must agree with the reports we just checked.
            if (!Objects.equals(lostAuthor, candidate.lostReporterId())
                    || !Objects.equals(foundAuthor, candidate.foundReporterId())) {
                continue;
            }
            if (Objects.equals(lostAuthor, foundAuthor)) {
                // A person cannot be alerted about matching their own reports.
                continue;
            }

            String dedupeKey = "match:" + lost.getId() + ":" + found.getId();
            activePairsByRecipient.computeIfAbsent(lostAuthor, key -> new HashSet<>()).add(dedupeKey);
            activePairsByRecipient.computeIfAbsent(foundAuthor, key -> new HashSet<>()).add(dedupeKey);
            accepted.add(new AcceptedPair(dedupeKey, lost, found, candidate, lostAuthor, foundAuthor));
        }

        for (AcceptedPair pair : accepted) {
            deliverMatch(pair, pair.lostAuthor());
            deliverMatch(pair, pair.foundAuthor());
        }

        // Deactivate pairs that this source no longer reports, preserving read
        // state and history. A pair that survives keeps its original createdAt.
        for (Notification existing : notifications.findMatchRowsForSource(
                NotificationType.MATCH_FOUND, event.itemId())) {
            Set<String> activeKeys = activePairsByRecipient.getOrDefault(
                    existing.getRecipientId(), Set.of());
            if (existing.isActive() && !activeKeys.contains(existing.getDedupeKey())) {
                existing.setActive(false);
                notifications.save(existing);
            }
        }
    }

    /** Delivers committed claim status changes. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleClaimChanged(ClaimChangedEvent event) {
        if (event == null || event.claimId() == null || event.status() == null) {
            return;
        }
        Claim claim = claims.findById(event.claimId()).orElse(null);
        if (claim == null || claim.getStatus() != event.status()) {
            // The claim moved on before this listener ran; do not send a stale alert.
            return;
        }
        if (event.handoverVerified() && !claim.isHandoverVerified()) {
            return;
        }

        Item item = claim.getItem();
        Long claimantId = claim.getClaimant().getId();
        Long reporterId = item.getReporter().getId();
        String title = item.getTitle();

        switch (event.status()) {
            case APPROVED -> deliverClaim(claim, claimantId, NotificationType.CLAIM_APPROVED,
                    "approved", "Claim approved",
                    "Your claim for \"" + title + "\" was approved. Arrange pickup with the finder.");
            case REJECTED -> deliverClaim(claim, claimantId, NotificationType.CLAIM_REJECTED,
                    "rejected", "Claim declined",
                    "Your response to \"" + title + "\" was declined. You can respond to other reports.");
            case COMPLETED -> {
                if (event.handoverVerified()) {
                    String body = "The pickup for \"" + title
                            + "\" was verified. Both reports are now resolved.";
                    deliverClaim(claim, claimantId, NotificationType.HANDOVER_COMPLETED,
                            "completed", "Pickup complete", body);
                    if (!Objects.equals(claimantId, reporterId)) {
                        deliverClaim(claim, reporterId, NotificationType.HANDOVER_COMPLETED,
                                "completed", "Pickup complete", body);
                    }
                } else if (item.getType() == ItemType.LOST) {
                    // A response to a lost report is resolved directly by its author.
                    deliverClaim(claim, claimantId, NotificationType.CLAIM_APPROVED,
                            "approved", "Response accepted",
                            "The reporter of \"" + title
                                    + "\" accepted your response and marked the report resolved.");
                }
            }
            default -> {
                // PENDING and CANCELLED transitions are not alert-worthy tonight.
            }
        }
    }

    @Transactional(readOnly = true)
    public NotificationPageResponse page(User user, int limit, Long beforeId) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LIMIT",
                    "Limit must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        if (beforeId != null && beforeId <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                    "beforeId must be a positive notification ID.");
        }

        List<Notification> rows = notifications.findVisiblePage(
                user.getId(), beforeId, NotificationType.MATCH_FOUND,
                ItemStatus.OPEN, PageRequest.of(0, limit + 1));
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        long unread = notifications.countVisibleUnread(
                user.getId(), NotificationType.MATCH_FOUND, ItemStatus.OPEN);
        Long nextBeforeId = hasMore && !rows.isEmpty()
                ? rows.get(rows.size() - 1).getId()
                : null;

        return new NotificationPageResponse(toResponses(rows), unread, nextBeforeId);
    }

    @Transactional
    public NotificationResponse markRead(User user, Long notificationId) {
        Notification notification = notifications
                .findByIdAndRecipientId(notificationId, user.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Notification not found."));
        if (notification.getReadAt() == null) {
            // DATETIME(6) keeps microseconds; truncate so a repeated read and
            // the stored row report the exact same instant.
            notification.setReadAt(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
            notifications.save(notification);
        }
        return toResponse(notification);
    }

    @Transactional
    public UnreadCountResponse markAllRead(User user) {
        Instant now = Instant.now();
        // The cutoff keeps a notification committed after this moment unread.
        notifications.markAllRead(user.getId(), now, now);
        return new UnreadCountResponse(0);
    }

    private void deliverMatch(AcceptedPair pair, Long recipientId) {
        boolean lostAuthor = Objects.equals(recipientId, pair.lostAuthor());
        String title = lostAuthor
                ? "Possible match for \"" + pair.lost().getTitle() + "\""
                : "Possible owner for \"" + pair.found().getTitle() + "\"";
        String reasons = reasonText(pair.candidate().reasons());
        String body = lostAuthor
                ? "A newly found report may match your lost report. Matched on: "
                        + reasons + ". Open it to check."
                : "A lost report may match your found report. Matched on: "
                        + reasons + ". Open your report to review.";

        upsert(NotificationType.MATCH_FOUND, recipientId, pair.dedupeKey(),
                pair.lost().getId(), pair.found().getId(), null,
                pair.candidate().lostItemVersion(), pair.candidate().foundItemVersion(),
                pair.candidate().score(), reasons,
                truncate(title, TITLE_LIMIT), truncate(body, BODY_LIMIT));
    }

    private void deliverClaim(Claim claim, Long recipientId, NotificationType type,
                              String statusKey, String title, String body) {
        upsert(type, recipientId, "claim:" + claim.getId() + ":" + statusKey,
                null, null, claim.getId(), null, null, null, null,
                truncate(title, TITLE_LIMIT), truncate(body, BODY_LIMIT));
    }

    /**
     * One atomic upsert. The unique key makes concurrent and replayed
     * deliveries safe; createdAt and readAt are deliberately not in the update
     * list, so re-delivery never resets read state or reorders the inbox.
     */
    private void upsert(NotificationType type, Long recipientId, String dedupeKey,
                        Long lostItemId, Long foundItemId, Long claimId,
                        Long lostItemVersion, Long foundItemVersion, Integer score,
                        String reasons, String title, String body) {
        jdbc.update("""
                INSERT INTO notifications
                    (recipient_id, type, dedupe_key, lost_item_id, found_item_id, claim_id,
                     lost_item_version, found_item_version, score, reasons, title, body,
                     active, created_at, read_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, UTC_TIMESTAMP(6), NULL)
                ON DUPLICATE KEY UPDATE
                    type = VALUES(type),
                    lost_item_id = VALUES(lost_item_id),
                    found_item_id = VALUES(found_item_id),
                    claim_id = VALUES(claim_id),
                    lost_item_version = VALUES(lost_item_version),
                    found_item_version = VALUES(found_item_version),
                    score = VALUES(score),
                    reasons = VALUES(reasons),
                    title = VALUES(title),
                    body = VALUES(body),
                    active = TRUE
                """, ps -> {
            ps.setLong(1, recipientId);
            ps.setString(2, type.name());
            ps.setString(3, dedupeKey);
            setNullableLong(ps, 4, lostItemId);
            setNullableLong(ps, 5, foundItemId);
            setNullableLong(ps, 6, claimId);
            setNullableLong(ps, 7, lostItemVersion);
            setNullableLong(ps, 8, foundItemVersion);
            setNullableInt(ps, 9, score);
            ps.setString(10, reasons);
            ps.setString(11, title);
            ps.setString(12, body);
        });
    }

    private List<NotificationResponse> toResponses(List<Notification> rows) {
        Set<Long> claimIds = rows.stream()
                .map(Notification::getClaimId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Claim> claimsById = claims.findAllById(claimIds).stream()
                .collect(Collectors.toMap(Claim::getId, claim -> claim));

        Set<Long> lostItemIds = rows.stream()
                .filter(row -> row.getType() == NotificationType.MATCH_FOUND)
                .map(Notification::getLostItemId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Item> lostItemsById = items.findAllById(lostItemIds).stream()
                .collect(Collectors.toMap(Item::getId, item -> item));

        return rows.stream()
                .map(row -> toResponse(row, claimsById, lostItemsById))
                .toList();
    }

    private NotificationResponse toResponse(Notification row) {
        if (row.getType() == NotificationType.MATCH_FOUND) {
            Item lost = row.getLostItemId() == null
                    ? null
                    : items.findById(row.getLostItemId()).orElse(null);
            Map<Long, Item> lostItems = lost == null ? Map.of() : Map.of(lost.getId(), lost);
            return toResponse(row, Map.of(), lostItems);
        }
        Claim claim = row.getClaimId() == null
                ? null
                : claims.findById(row.getClaimId()).orElse(null);
        Map<Long, Claim> claimsById = claim == null ? Map.of() : Map.of(claim.getId(), claim);
        return toResponse(row, claimsById, Map.of());
    }

    private NotificationResponse toResponse(Notification row, Map<Long, Claim> claimsById,
                                            Map<Long, Item> lostItemsById) {
        if (row.getType() == NotificationType.MATCH_FOUND) {
            Item lost = lostItemsById.get(row.getLostItemId());
            Long lostReportId = lost != null
                    && Objects.equals(lost.getReporter().getId(), row.getRecipientId())
                    ? lost.getId()
                    : null;
            return new NotificationResponse(row.getId(), row.getType(), row.getTitle(),
                    row.getBody(), row.getFoundItemId(), null, lostReportId,
                    row.getCreatedAt(), row.getReadAt());
        }
        Claim claim = claimsById.get(row.getClaimId());
        Long itemId = claim == null ? null : claim.getItem().getId();
        return new NotificationResponse(row.getId(), row.getType(), row.getTitle(),
                row.getBody(), itemId, row.getClaimId(), null,
                row.getCreatedAt(), row.getReadAt());
    }

    private boolean eligible(Item item, Long expectedVersion) {
        return item != null
                && !item.isDeleted()
                && item.getStatus() == ItemStatus.OPEN
                && expectedVersion != null
                && item.getVersion() == expectedVersion;
    }

    private String reasonText(List<String> reasons) {
        if (reasons == null) {
            return "similar details";
        }
        String joined = reasons.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(reason -> !reason.isEmpty())
                .limit(3)
                .collect(Collectors.joining(", "));
        return joined.isEmpty() ? "similar details" : joined;
    }

    private static String truncate(String value, int limit) {
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit - 1) + "…";
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private record AcceptedPair(String dedupeKey, Item lost, Item found,
                                MatchCandidate candidate, Long lostAuthor, Long foundAuthor) {
    }
}
