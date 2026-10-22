package com.lostfound.matching;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Bounded restart catch-up. Runs once the application is ready so reports that
 * existed before the matching feature shipped (or before a restart) get a
 * current snapshot and a MatchCandidatesChangedEvent, even if nothing changed.
 */
@Component
public class MatchCatchUpRunner {

    private static final Logger log = LoggerFactory.getLogger(MatchCatchUpRunner.class);

    private final MatchRefreshService refreshService;
    private final boolean enabled;

    public MatchCatchUpRunner(MatchRefreshService refreshService,
                              @Value("${app.matching.catch-up-on-startup:true}") boolean enabled) {
        this.refreshService = refreshService;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) {
            return;
        }
        try {
            refreshService.refreshAll();
        } catch (RuntimeException ex) {
            log.warn("Match catch-up failed at startup; after-commit refresh and retry remain active", ex);
        }
    }
}
