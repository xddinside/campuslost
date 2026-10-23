package com.lostfound.notifications;

import com.lostfound.contract.ClaimChangedEvent;
import com.lostfound.contract.MatchCandidatesChangedEvent;
import com.lostfound.model.ClaimStatus;
import com.lostfound.model.ItemType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Retains deliveries that failed after commit and replays them on a schedule.
 * The notification upsert dedupe keys make every replay idempotent, so a row
 * is deleted only after its handler run succeeds.
 */
@Service
public class NotificationRetryService {

    private static final Logger log = LoggerFactory.getLogger(NotificationRetryService.class);
    private static final int BATCH = 20;
    private static final long MAX_BACKOFF_SECONDS = 600;

    private final NotificationRetryRepository retries;
    private final NotificationService notifications;

    public NotificationRetryService(NotificationRetryRepository retries,
                                    NotificationService notifications) {
        this.retries = retries;
        this.notifications = notifications;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(MatchCandidatesChangedEvent event, String error) {
        // Only the newest failed snapshot per report is worth replaying: the
        // handler reconciles against the persisted candidate table anyway.
        retries.deleteByKindAndItemId(NotificationRetry.Kind.MATCH_SNAPSHOT, event.itemId());
        NotificationRetry row = fresh(error);
        row.setKind(NotificationRetry.Kind.MATCH_SNAPSHOT);
        row.setItemId(event.itemId());
        row.setItemVersion(event.itemVersion());
        retries.save(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(ClaimChangedEvent event, String error) {
        NotificationRetry row = fresh(error);
        row.setKind(NotificationRetry.Kind.CLAIM_CHANGED);
        row.setClaimId(event.claimId());
        row.setItemId(event.itemId());
        row.setItemType(event.itemType() == null ? null : event.itemType().name());
        row.setLostReportId(event.lostReportId());
        row.setClaimantId(event.claimantId());
        row.setReporterId(event.reporterId());
        row.setClaimStatus(event.status() == null ? null : event.status().name());
        row.setHandoverVerified(event.handoverVerified());
        retries.save(row);
    }

    @Scheduled(fixedDelayString = "${app.notifications.retry-interval-ms:15000}")
    public void drain() {
        List<NotificationRetry> due = retries.findDue(Instant.now(), PageRequest.of(0, BATCH));
        for (NotificationRetry row : due) {
            try {
                replay(row);
                retries.delete(row);
            } catch (RuntimeException ex) {
                long delay = Math.min(30L * (row.getAttempts() + 1), MAX_BACKOFF_SECONDS);
                try {
                    retries.markAttempt(row.getId(), abbreviate(ex.getMessage()),
                            Instant.now().plus(Duration.ofSeconds(delay)));
                } catch (RuntimeException markFailure) {
                    log.warn("Could not mark notification retry {} failed", row.getId(), markFailure);
                }
            }
        }
    }

    private void replay(NotificationRetry row) {
        if (row.getKind() == NotificationRetry.Kind.MATCH_SNAPSHOT) {
            notifications.handleMatchSnapshot(new MatchCandidatesChangedEvent(
                    row.getItemId(), row.getItemVersion() == null ? 0 : row.getItemVersion(),
                    List.of()));
            return;
        }
        notifications.handleClaimChanged(new ClaimChangedEvent(
                row.getClaimId(), row.getItemId(),
                row.getItemType() == null ? null : ItemType.valueOf(row.getItemType()),
                row.getLostReportId(), row.getClaimantId(), row.getReporterId(),
                row.getClaimStatus() == null ? null : ClaimStatus.valueOf(row.getClaimStatus()),
                Boolean.TRUE.equals(row.getHandoverVerified())));
    }

    private NotificationRetry fresh(String error) {
        NotificationRetry row = new NotificationRetry();
        row.setAttempts(0);
        row.setLastError(abbreviate(error));
        Instant now = Instant.now();
        row.setCreatedAt(now);
        // Due on the next drain tick; failures push it back progressively.
        row.setNextAttemptAt(now);
        return row;
    }

    private static String abbreviate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
