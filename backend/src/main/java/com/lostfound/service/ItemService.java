package com.lostfound.service;

import com.lostfound.dto.ItemRequest;
import com.lostfound.dto.ItemResponse;
import com.lostfound.exception.ApiException;
import com.lostfound.model.ClaimStatus;
import com.lostfound.model.Item;
import com.lostfound.model.ItemStatus;
import com.lostfound.model.ItemType;
import com.lostfound.model.User;
import com.lostfound.repository.ClaimRepository;
import com.lostfound.repository.ItemRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class ItemService {

    private final ItemRepository itemRepository;
    private final ClaimRepository claimRepository;

    public ItemService(ItemRepository itemRepository, ClaimRepository claimRepository) {
        this.itemRepository = itemRepository;
        this.claimRepository = claimRepository;
    }

    @Transactional(readOnly = true)
    public List<ItemResponse> list(ItemType type, String category, String location, String q) {
        return itemRepository.search(type, blankToNull(category), blankToNull(location), blankToNull(q))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ItemResponse get(Long id) {
        return toResponse(findItem(id));
    }

    @Transactional
    public ItemResponse create(User user, ItemRequest request) {
        Item item = new Item();
        item.setType(request.type());
        item.setTitle(request.title().trim());
        item.setDescription(request.description().trim());
        item.setCategory(request.category().trim());
        item.setLocation(request.location().trim());
        item.setDateOccurred(request.dateOccurred());
        item.setStatus(ItemStatus.OPEN);
        item.setReporter(user);
        itemRepository.save(item);
        return toResponse(item);
    }

    @Transactional
    public ItemResponse updateStatus(User user, Long id, ItemStatus status) {
        Item item = findItem(id);
        checkOwnerOrAdmin(user, item);
        if (status == ItemStatus.OPEN && item.getStatus() == ItemStatus.OPEN) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Item is already open");
        }
        if (item.getStatus() == ItemStatus.CLOSED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Closed items cannot be changed");
        }
        item.setStatus(status);
        if (status == ItemStatus.RESOLVED) {
            item.setResolvedAt(Instant.now());
        }
        itemRepository.save(item);
        return toResponse(item);
    }

    @Transactional
    public void delete(User user, Long id) {
        Item item = findItem(id);
        checkOwnerOrAdmin(user, item);
        claimRepository.deleteAll(claimRepository.findByItemIdOrderByCreatedAtDesc(id));
        itemRepository.delete(item);
    }

    @Transactional(readOnly = true)
    public List<ItemResponse> mine(User user) {
        return itemRepository.findByReporterIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public Item findItem(Long id) {
        return itemRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Item not found"));
    }

    private void checkOwnerOrAdmin(User user, Item item) {
        if (!item.getReporter().getId().equals(user.getId()) && user.getRole().name().equals("USER")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only manage your own items");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ItemResponse toResponse(Item item) {
        return new ItemResponse(
                item.getId(),
                item.getType(),
                item.getTitle(),
                item.getDescription(),
                item.getCategory(),
                item.getLocation(),
                item.getDateOccurred(),
                item.getStatus(),
                item.getReporter().getId(),
                item.getReporter().getName(),
                item.getReporter().getEmail(),
                item.getReporter().getPhone(),
                item.getCreatedAt(),
                item.getResolvedAt(),
                claimRepository.countByItemIdAndStatus(item.getId(), ClaimStatus.PENDING));
    }
}
