package com.lostfound.notifications;

import com.lostfound.contract.ClaimChangedEvent;
import com.lostfound.contract.MatchCandidatesChangedEvent;
import com.lostfound.matching.MatchRetryQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Runs only after the writing transaction has committed and starts a fresh
 * transaction for delivery. A notification failure must never turn a
 * successful report or claim change into an error response, so exceptions are
 * logged — but the failed delivery is retained and replayed by
 * {@link NotificationRetryService}, since the upsert dedupe keys make replays
 * idempotent. A failed match delivery also re-enters the matching retry queue
 * so the underlying refresh is retried too.
 */
@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final NotificationService notifications;
    private final NotificationRetryService retries;
    private final MatchRetryQueue matchRetries;

    public NotificationEventListener(NotificationService notifications,
                                     NotificationRetryService retries,
                                     MatchRetryQueue matchRetries) {
        this.notifications = notifications;
        this.retries = retries;
        this.matchRetries = matchRetries;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMatchCandidatesChanged(MatchCandidatesChangedEvent event) {
        try {
            notifications.handleMatchSnapshot(event);
        } catch (RuntimeException ex) {
            log.error("Could not deliver match alerts for report {}", event.itemId(), ex);
            retain(() -> {
                retries.record(event, ex.getMessage());
                matchRetries.enqueue(event.itemId());
            });
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onClaimChanged(ClaimChangedEvent event) {
        try {
            notifications.handleClaimChanged(event);
        } catch (RuntimeException ex) {
            log.error("Could not deliver claim alert for claim {}", event.claimId(), ex);
            retain(() -> retries.record(event, ex.getMessage()));
        }
    }

    private void retain(Runnable record) {
        try {
            record.run();
        } catch (RuntimeException ex) {
            log.error("Could not retain a failed notification delivery", ex);
        }
    }
}
