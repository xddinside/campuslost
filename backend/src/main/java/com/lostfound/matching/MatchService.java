package com.lostfound.matching;

import com.lostfound.model.Role;
import com.lostfound.model.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import com.lostfound.dto.ItemResponse;
import com.lostfound.exception.ApiException;
import com.lostfound.mapper.PublicItemMapper;
import com.lostfound.model.Item;
import com.lostfound.model.ItemStatus;
import com.lostfound.model.ItemType;
import com.lostfound.repository.ItemRepository;

@Service
public class MatchService {

    private final ItemRepository itemRepository;
    private final MatchCandidateRepository candidateRepository;
    private final PublicItemMapper mapper;

    public MatchService(ItemRepository itemRepository,
                        MatchCandidateRepository candidateRepository,
                        PublicItemMapper mapper) {
        this.itemRepository = itemRepository;
        this.candidateRepository = candidateRepository;
        this.mapper = mapper;
    }

    /**
     * Owner/admin view of current suggestions. Reads filter again on status,
     * reporter and source versions so a snapshot that lost eligibility is never
     * served while an after-commit refresh catches up.
     */
    @Transactional(readOnly = true)
    public MatchListResponse matches(User actor, Long itemId) {
        Item source = itemRepository.findByIdAndDeletedAtIsNull(itemId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Report not found."));
        boolean allowed = actor.getRole() == Role.ADMIN
                || source.getReporter().getId().equals(actor.getId());
        if (!allowed) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Only the reporter or an administrator can see matches for this report.");
        }
        if (source.getStatus() != ItemStatus.OPEN) {
            return new MatchListResponse(itemId, List.of());
        }

        List<MatchView> views = candidateRepository.findAllInvolving(itemId).stream()
                .filter(row -> current(row, source))
                .map(row -> toView(row, itemId))
                .sorted((left, right) -> {
                    int byScore = Integer.compare(right.score(), left.score());
                    if (byScore != 0) {
                        return byScore;
                    }
                    return Long.compare(candidateId(left, itemId), candidateId(right, itemId));
                })
                .limit(5)
                .toList();
        return new MatchListResponse(itemId, views);
    }

    private boolean current(MatchCandidateEntity row, Item source) {
        Item lost = row.getLostItem();
        Item found = row.getFoundItem();
        if (row.getScore() < MatchScoring.MIN_SCORE) {
            return false;
        }
        if (lost.getType() != ItemType.LOST || found.getType() != ItemType.FOUND) {
            return false;
        }
        if (lost.getStatus() != ItemStatus.OPEN || found.getStatus() != ItemStatus.OPEN) {
            return false;
        }
        if (lost.isDeleted() || found.isDeleted()) {
            return false;
        }
        if (!lost.getId().equals(source.getId()) && !found.getId().equals(source.getId())) {
            return false;
        }
        if (lost.getReporter().getId().equals(found.getReporter().getId())) {
            return false;
        }
        return row.getLostItemVersion() == lost.getVersion()
                && row.getFoundItemVersion() == found.getVersion();
    }

    private MatchView toView(MatchCandidateEntity row, Long sourceItemId) {
        Item lost = row.getLostItem();
        Item found = row.getFoundItem();
        Item candidateItem = lost.getId().equals(sourceItemId) ? found : lost;
        ItemResponse candidate = mapper.toResponse(candidateItem);
        List<String> reasons = row.getReasons() == null || row.getReasons().isBlank()
                ? List.of()
                : List.of(row.getReasons().split("\n"));
        return new MatchView(candidate, lost.getId(), found.getId(), row.getScore(), reasons);
    }

    private long candidateId(MatchView view, Long itemId) {
        return view.lostItemId().equals(itemId) ? view.foundItemId() : view.lostItemId();
    }
}
