package com.lostfound.notifications;

import com.lostfound.contract.ClaimChangedEvent;
import com.lostfound.contract.MatchCandidatesChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Runs only after the writing transaction has committed and starts a fresh
 * transaction for delivery. A notification failure must never turn a successful
 * report or claim change into an error response, so exceptions are logged.
 */
@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final NotificationService notifications;

    public NotificationEventListener(NotificationService notifications) {
        this.notifications = notifications;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMatchCandidatesChanged(MatchCandidatesChangedEvent event) {
        try {
            notifications.handleMatchSnapshot(event);
        } catch (RuntimeException ex) {
            log.error("Could not deliver match alerts for report {}", event.itemId(), ex);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onClaimChanged(ClaimChangedEvent event) {
        try {
            notifications.handleClaimChanged(event);
        } catch (RuntimeException ex) {
            log.error("Could not deliver claim alert for claim {}", event.claimId(), ex);
        }
    }
}
