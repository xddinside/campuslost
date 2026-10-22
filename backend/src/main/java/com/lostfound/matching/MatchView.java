package com.lostfound.matching;

import com.lostfound.dto.ItemResponse;

import java.util.List;

/** One suggestion shown to a report's owner. */
public record MatchView(
        ItemResponse item,
        Long lostItemId,
        Long foundItemId,
        int score,
        List<String> reasons
) {
}
