package com.lostfound.matching;

import com.lostfound.contract.ItemChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Runs after the report transaction commits and starts a fresh matching
 * transaction. A failure never rolls back the user's report; it is queued for
 * the bounded retry task instead of being forgotten.
 */
@Component
public class MatchRefreshListener {

    private static final Logger log = LoggerFactory.getLogger(MatchRefreshListener.class);

    private final MatchRefreshService refreshService;
    private final MatchRetryQueue retryQueue;

    public MatchRefreshListener(MatchRefreshService refreshService, MatchRetryQueue retryQueue) {
        this.refreshService = refreshService;
        this.retryQueue = retryQueue;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onItemChanged(ItemChangedEvent event) {
        try {
            refreshService.refreshForItem(event.itemId());
        } catch (RuntimeException ex) {
            log.warn("Match refresh failed for report {} (version {}, {}); queued for retry",
                    event.itemId(), event.version(), event.kind(), ex);
            retryQueue.enqueue(event.itemId());
        }
    }
}
