package com.lostfound.contract;

import java.util.List;

/** One canonical LOST -> FOUND candidate pair. */
public record MatchCandidate(
        Long lostItemId,
        Long foundItemId,
        Long lostReporterId,
        Long foundReporterId,
        int score,
        List<String> reasons,
        long lostItemVersion,
        long foundItemVersion
) {
}
