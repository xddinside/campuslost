package com.lostfound.contract;

import java.util.List;

public record MatchCandidatesChangedEvent(
        Long itemId,
        long itemVersion,
        List<MatchCandidate> candidates
) {
}
