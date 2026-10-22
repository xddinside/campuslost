package com.lostfound.controller;

import com.lostfound.dto.ClaimResponse;
import com.lostfound.dto.ItemResponse;
import com.lostfound.model.ClaimStatus;
import com.lostfound.model.Item;
import com.lostfound.model.User;
import com.lostfound.repository.ClaimRepository;
import com.lostfound.repository.ItemRepository;
import com.lostfound.repository.UserRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ItemRepository itemRepository;
    private final ClaimRepository claimRepository;
    private final UserRepository userRepository;

    public AdminController(ItemRepository itemRepository,
                           ClaimRepository claimRepository,
                           UserRepository userRepository) {
        this.itemRepository = itemRepository;
        this.claimRepository = claimRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    @GetMapping("/stats")
    public StatsResponse stats(@AuthenticationPrincipal User user) {
        return new StatsResponse(
                itemRepository.count(),
                itemRepository.findAll().stream().filter(i -> i.getStatus().name().equals("OPEN")).count(),
                itemRepository.findAll().stream().filter(i -> i.getStatus().name().equals("RESOLVED")).count(),
                claimRepository.count(),
                userRepository.count());
    }

    @Transactional(readOnly = true)
    @GetMapping("/items")
    public List<ItemResponse> allItems(@AuthenticationPrincipal User user) {
        return itemRepository.findAll().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    @GetMapping("/items/{itemId}/claims")
    public List<ClaimResponse> claimsForItem(@AuthenticationPrincipal User user,
                                             @PathVariable Long itemId) {
        return claimRepository.findByItemIdOrderByCreatedAtDesc(itemId).stream()
                .map(c -> new ClaimResponse(
                        c.getId(), c.getItem().getId(), c.getItem().getTitle(),
                        c.getClaimant().getId(), c.getClaimant().getName(),
                        c.getClaimant().getEmail(), c.getMessage(), c.getStatus(), c.getCreatedAt()))
                .toList();
    }

    private ItemResponse toResponse(Item item) {
        return new ItemResponse(
                item.getId(), item.getType(), item.getTitle(), item.getDescription(),
                item.getCategory(), item.getLocation(), item.getDateOccurred(), item.getStatus(),
                item.getReporter().getId(), item.getReporter().getName(),
                item.getReporter().getEmail(), item.getReporter().getPhone(),
                item.getCreatedAt(), item.getResolvedAt(),
                claimRepository.countByItemIdAndStatus(item.getId(), ClaimStatus.PENDING));
    }

    public record StatsResponse(long items, long open, long resolved, long claims, long users) {
    }
}
