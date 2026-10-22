package com.lostfound.matching;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * In-process retry queue for after-commit refreshes. At most one entry per
 * report is queued at a time; a failed retry goes back to the tail so a
 * transient database problem recovers on the next interval.
 */
@Component
public class MatchRetryQueue {

    private static final Logger log = LoggerFactory.getLogger(MatchRetryQueue.class);

    private final ConcurrentLinkedQueue<Long> queue = new ConcurrentLinkedQueue<>();
    private final Set<Long> queued = ConcurrentHashMap.newKeySet();
    private final MatchRefreshService refreshService;
    private final int batchSize;

    public MatchRetryQueue(MatchRefreshService refreshService,
                           @Value("${app.matching.retry-batch-size:20}") int batchSize) {
        this.refreshService = refreshService;
        this.batchSize = Math.max(1, batchSize);
    }

    public void enqueue(Long itemId) {
        if (itemId != null && queued.add(itemId)) {
            queue.add(itemId);
        }
    }

    public int size() {
        return queue.size();
    }

    @Scheduled(fixedDelayString = "${app.matching.retry-interval-ms:30000}")
    public void drain() {
        for (int attempts = 0; attempts < batchSize; attempts++) {
            Long itemId = queue.poll();
            if (itemId == null) {
                return;
            }
            queued.remove(itemId);
            try {
                refreshService.refreshForItem(itemId);
            } catch (RuntimeException ex) {
                log.warn("Match retry failed for report {}; will try again", itemId, ex);
                enqueue(itemId);
                return;
            }
        }
    }
}
