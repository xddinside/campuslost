package com.lostfound.matching;

import java.util.List;

public record MatchListResponse(Long itemId, List<MatchView> matches) {
}
