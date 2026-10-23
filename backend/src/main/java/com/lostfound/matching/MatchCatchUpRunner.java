package com.lostfound.matching;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded restart catch-up. Runs once the application is ready so reports that
 * existed before the matching feature shipped (or before a restart) get a
 * current snapshot and a MatchCandidatesChangedEvent, even if nothing changed.
 * A failure — including enumeration failing before any batch ran — is retried
 * by the scheduled pass instead of being dropped after one warning.
 */
@Component
public class MatchCatchUpRunner {

    private static final Logger log = LoggerFactory.getLogger(MatchCatchUpRunner.class);

    private final MatchRefreshService refreshService;
    private final boolean enabled;
    private final int maxRetries;
    private final AtomicInteger pending = new AtomicInteger();

    public MatchCatchUpRunner(MatchRefreshService refreshService,
                              @Value("${app.matching.catch-up-on-startup:true}") boolean enabled,
                              @Value("${app.matching.catch-up-retry-attempts:20}") int maxRetries) {
        this.refreshService = refreshService;
        this.enabled = enabled;
        this.maxRetries = Math.max(0, maxRetries);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (enabled) {
            run();
        }
    }

    @Scheduled(fixedDelayString = "${app.matching.catch-up-retry-interval-ms:30000}")
    public void retry() {
        if (pending.get() > 0) {
            run();
        }
    }

    private void run() {
        try {
            refreshService.refreshAll();
            pending.set(0);
        } catch (RuntimeException ex) {
            int attempt = pending.incrementAndGet();
            if (attempt <= maxRetries) {
                log.warn("Match catch-up failed (attempt {}/{}); retry scheduled",
                        attempt, maxRetries + 1, ex);
            } else {
                pending.set(0);
                log.error("Match catch-up gave up after {} attempts; next restart will try again",
                        attempt, ex);
            }
        }
    }
}
