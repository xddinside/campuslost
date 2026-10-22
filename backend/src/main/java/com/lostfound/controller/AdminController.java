package com.lostfound.controller;

import com.lostfound.dto.ClaimResponse;
import com.lostfound.dto.ItemResponse;
import com.lostfound.mapper.ClaimMapper;
import com.lostfound.mapper.PublicItemMapper;
import com.lostfound.model.ClaimStatus;
import com.lostfound.model.Item;
import com.lostfound.model.ItemStatus;
import com.lostfound.model.User;
import com.lostfound.photo.PhotoStorageService;
import com.lostfound.repository.ClaimRepository;
import com.lostfound.repository.ItemRepository;
import com.lostfound.repository.UserRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ItemRepository itemRepository;
    private final ClaimRepository claimRepository;
    private final UserRepository userRepository;
    private final PublicItemMapper itemMapper;
    private final ClaimMapper claimMapper;
    private final PhotoStorageService photoStorageService;

    public AdminController(ItemRepository itemRepository,
                           ClaimRepository claimRepository,
                           UserRepository userRepository,
                           PublicItemMapper itemMapper,
                           ClaimMapper claimMapper,
                           PhotoStorageService photoStorageService) {
        this.itemRepository = itemRepository;
        this.claimRepository = claimRepository;
        this.userRepository = userRepository;
        this.itemMapper = itemMapper;
        this.claimMapper = claimMapper;
        this.photoStorageService = photoStorageService;
    }

    @Transactional(readOnly = true)
    @GetMapping("/stats")
    public StatsResponse stats(@AuthenticationPrincipal User user) {
        List<Item> items = itemRepository.findAllVisibleOrderByCreatedAtDesc();
        long open = items.stream().filter(item -> item.getStatus() == ItemStatus.OPEN).count();
        long reserved = items.stream().filter(item -> item.getStatus() == ItemStatus.RESERVED).count();
        long resolved = items.stream().filter(item -> item.getStatus() == ItemStatus.RESOLVED).count();
        long closed = items.stream().filter(item -> item.getStatus() == ItemStatus.CLOSED).count();
        return new StatsResponse(items.size(), open, reserved, resolved, closed,
                claimRepository.count(), userRepository.count());
    }

    @Transactional(readOnly = true)
    @GetMapping("/items")
    public List<ItemResponse> allItems(@AuthenticationPrincipal User user) {
        return itemRepository.findAllVisibleOrderByCreatedAtDesc().stream()
                .map(itemMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    @GetMapping("/items/{itemId}/claims")
    public List<ClaimResponse> claimsForItem(@AuthenticationPrincipal User user,
                                             @PathVariable Long itemId) {
        return claimRepository.findByItemIdOrderByCreatedAtDesc(itemId).stream()
                .map(claimMapper::toResponse)
                .toList();
    }

    @Transactional
    @PostMapping("/photos/cleanup")
    public Map<String, Integer> cleanupPhotos() {
        return Map.of("removed", photoStorageService.cleanupUnattached(24));
    }

    public record StatsResponse(long items, long open, long reserved, long resolved,
                                long closed, long claims, long users) {
    }
}
