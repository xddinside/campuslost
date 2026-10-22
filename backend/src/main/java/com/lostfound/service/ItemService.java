package com.lostfound.service;

import com.lostfound.contract.ItemChangeKind;
import com.lostfound.contract.ItemChangedEvent;
import com.lostfound.contract.ItemEvidencePolicy;
import com.lostfound.dto.*;
import com.lostfound.exception.ApiException;
import com.lostfound.mapper.PublicItemMapper;
import com.lostfound.model.*;
import com.lostfound.photo.PhotoStorageService;
import com.lostfound.repository.ClaimRepository;
import com.lostfound.repository.ItemRepository;
import com.lostfound.workflow.ClaimWorkflow;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
public class ItemService {

    private final ItemRepository itemRepository;
    private final ClaimRepository claimRepository;
    private final PhotoStorageService photoStorageService;
    private final PublicItemMapper mapper;
    private final ObjectProvider<ItemEvidencePolicy> evidencePolicy;
    private final ClaimWorkflow claimWorkflow;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;

    public ItemService(ItemRepository itemRepository,
                       ClaimRepository claimRepository,
                       PhotoStorageService photoStorageService,
                       PublicItemMapper mapper,
                       ObjectProvider<ItemEvidencePolicy> evidencePolicy,
                       ClaimWorkflow claimWorkflow,
                       ApplicationEventPublisher events,
                       EntityManager entityManager) {
        this.itemRepository = itemRepository;
        this.claimRepository = claimRepository;
        this.photoStorageService = photoStorageService;
        this.mapper = mapper;
        this.evidencePolicy = evidencePolicy;
        this.claimWorkflow = claimWorkflow;
        this.events = events;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public List<ItemResponse> list(ItemType type, ItemStatus status, String category,
                                   String location, String q) {
        String normalizedCategory = null;
        if (category != null && !category.isBlank()) {
            normalizedCategory = ItemRules.category(category);
        }
        ItemStatus effectiveStatus = status == null ? ItemStatus.OPEN : status;
        return itemRepository.search(type, effectiveStatus, normalizedCategory,
                        blankToNull(location), blankToNull(q))
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ItemResponse get(Long id) {
        return mapper.toResponse(findVisible(id));
    }

    @Transactional
    public ItemResponse create(User user, ItemRequest request) {
        String title = ItemRules.requiredText(request.title(), "INVALID_TITLE", "Title is required.");
        String description = ItemRules.requiredText(request.description(), "INVALID_DESCRIPTION",
                "Description is required.");
        String category = ItemRules.category(request.category());
        String location = ItemRules.requiredText(request.location(), "INVALID_LOCATION",
                "Location is required.");
        Instant occurredAt = ItemRules.occurredAt(request.dateOccurred());
        String question = ItemRules.question(request.ownershipQuestion());
        if (request.type() == ItemType.LOST && question != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUESTION",
                    "Lost reports cannot ask an ownership question.");
        }

        evidencePolicy.ifAvailable(policy -> policy.validate(user,
                new ItemEvidencePolicy.MergedItemInput(request.type(), title, description,
                        category, location, question, question != null)));

        Item item = new Item();
        item.setType(request.type());
        item.setTitle(title);
        item.setDescription(description);
        item.setCategory(category);
        item.setLocation(location);
        item.setDateOccurred(occurredAt);
        item.setOwnershipQuestion(question);
        item.setStatus(ItemStatus.OPEN);
        item.setReporter(user);
        itemRepository.save(item);

        photoStorageService.attach(user, item, request.photoId());
        entityManager.flush();
        publish(item, ItemChangeKind.CREATED);
        return mapper.toResponse(item);
    }

    @Transactional
    public ItemResponse update(User user, Long id, ItemPatchRequest request) {
        Item item = lockVisible(id);
        checkOwnerOrAdmin(user, item);
        if (item.getStatus() == ItemStatus.RESERVED) {
            throw new ApiException(HttpStatus.CONFLICT, "ITEM_RESERVED",
                    "Release the approved pickup before editing this report.");
        }
        if (item.getStatus() == ItemStatus.CLOSED) {
            throw new ApiException(HttpStatus.CONFLICT, "ITEM_CLOSED",
                    "Closed reports cannot be edited.");
        }
        if (request.getVersion() == null || request.getVersion() != item.getVersion()) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_VERSION",
                    "This report changed since you loaded it. Refresh and try again.");
        }
        if (request.hasType() && request.getType() != null && request.getType() != item.getType()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TYPE_IMMUTABLE",
                    "A report cannot change between lost and found.");
        }

        String title = item.getTitle();
        String description = item.getDescription();
        String category = item.getCategory();
        String location = item.getLocation();
        Instant occurredAt = item.getDateOccurred();
        if (request.hasTitle()) {
            title = ItemRules.requiredText(request.getTitle(), "INVALID_TITLE", "Title is required.");
        }
        if (request.hasDescription()) {
            description = ItemRules.requiredText(request.getDescription(), "INVALID_DESCRIPTION",
                    "Description is required.");
        }
        if (request.hasCategory()) {
            category = ItemRules.category(request.getCategory());
        }
        if (request.hasLocation()) {
            location = ItemRules.requiredText(request.getLocation(), "INVALID_LOCATION",
                    "Location is required.");
        }
        if (request.hasDateOccurred()) {
            occurredAt = ItemRules.occurredAt(request.getDateOccurred());
        }

        String question = item.getOwnershipQuestion();
        boolean questionChanged = false;
        if (request.hasOwnershipQuestion()) {
            String requestedQuestion = ItemRules.question(request.getOwnershipQuestion());
            if (!Objects.equals(requestedQuestion, item.getOwnershipQuestion())) {
                if (claimRepository.countByItemIdAndStatus(item.getId(), ClaimStatus.PENDING) > 0) {
                    throw new ApiException(HttpStatus.CONFLICT, "QUESTION_FROZEN",
                            "Decide or wait for pending responses before changing the ownership question.");
                }
                question = requestedQuestion;
                questionChanged = true;
            }
        }
        if (item.getType() == ItemType.LOST && question != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUESTION",
                    "Lost reports cannot ask an ownership question.");
        }

        final String mergedQuestion = question;
        final boolean mergedQuestionProvided = request.hasOwnershipQuestion() || questionChanged;
        final String mergedTitle = title;
        final String mergedDescription = description;
        final String mergedCategory = category;
        final String mergedLocation = location;
        if (mergedQuestionProvided) {
            evidencePolicy.ifAvailable(policy -> policy.validate(user,
                    new ItemEvidencePolicy.MergedItemInput(item.getType(), mergedTitle, mergedDescription,
                            mergedCategory, mergedLocation, mergedQuestion, true)));
        }

        if (request.hasPhotoId()) {
            String requestedPhoto = blankToNull(request.getPhotoId());
            String currentPhoto = item.getPhotoId();
            if (!Objects.equals(requestedPhoto, currentPhoto)) {
                if (requestedPhoto == null) {
                    item.setPhotoId(null);
                    photoStorageService.detachAndDelete(currentPhoto);
                } else {
                    photoStorageService.attach(user, item, requestedPhoto);
                    photoStorageService.detachAndDelete(currentPhoto);
                }
            }
        }

        item.setTitle(title);
        item.setDescription(description);
        item.setCategory(category);
        item.setLocation(location);
        item.setDateOccurred(occurredAt);
        item.setOwnershipQuestion(question);
        itemRepository.save(item);
        entityManager.flush();
        publish(item, ItemChangeKind.UPDATED);
        return mapper.toResponse(item);
    }

    @Transactional
    public ItemResponse updateStatus(User user, Long id, ItemStatus status) {
        Item item = lockVisible(id);
        checkOwnerOrAdmin(user, item);
        if (status == ItemStatus.RESERVED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS",
                    "Reservations are created by approving a claim.");
        }
        if (item.getStatus() == ItemStatus.RESERVED) {
            throw new ApiException(HttpStatus.CONFLICT, "ITEM_RESERVED",
                    "Release the approved pickup before changing this report's status.");
        }
        if (item.getStatus() == ItemStatus.CLOSED) {
            throw new ApiException(HttpStatus.CONFLICT, "ITEM_CLOSED",
                    "Closed reports are final and cannot change.");
        }
        if (item.getStatus() == status) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_STATUS_CHANGE",
                    "This report already has that status.");
        }

        if (status == ItemStatus.OPEN) {
            if (claimRepository.countVerifiedHandoverForItem(id) > 0) {
                throw new ApiException(HttpStatus.CONFLICT, "PICKUP_COMPLETED",
                        "A completed pickup cannot be reopened.");
            }
            item.setResolvedAt(null);
        } else if (status == ItemStatus.RESOLVED) {
            claimWorkflow.rejectPendingForItem(item, null);
            item.setResolvedAt(Instant.now());
        } else if (status == ItemStatus.CLOSED) {
            claimWorkflow.rejectPendingForItem(item, null);
            if (item.getStatus() == ItemStatus.OPEN) {
                item.setResolvedAt(null);
            }
        }
        item.setStatus(status);
        itemRepository.save(item);
        entityManager.flush();
        publish(item, ItemChangeKind.STATUS_CHANGED);
        return mapper.toResponse(item);
    }

    @Transactional
    public void delete(User user, Long id) {
        Item item = lockVisible(id);
        checkOwnerOrAdmin(user, item);
        if (item.getStatus() == ItemStatus.RESERVED) {
            throw new ApiException(HttpStatus.CONFLICT, "ITEM_RESERVED",
                    "Release the approved pickup before deleting this report.");
        }
        claimWorkflow.rejectPendingForItem(item, null);
        item.setDeletedAt(Instant.now());
        itemRepository.save(item);
        entityManager.flush();
        publish(item, ItemChangeKind.DELETED);
    }

    @Transactional(readOnly = true)
    public List<ItemResponse> mine(User user) {
        return itemRepository.findVisibleByReporterIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ItemEditResponse editView(User user, Long id) {
        Item item = findVisible(id);
        checkOwnerOrAdmin(user, item);
        return new ItemEditResponse(
                item.getId(),
                item.getType(),
                item.getTitle(),
                item.getDescription(),
                item.getCategory(),
                item.getLocation(),
                item.getDateOccurred(),
                item.getStatus(),
                item.getVersion(),
                item.getPhotoId(),
                photoStorageService.url(item.getPhotoId()),
                item.getOwnershipQuestion()
        );
    }

    public Item findVisible(Long id) {
        return itemRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Report not found."));
    }

    private Item lockVisible(Long id) {
        Item item = itemRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Report not found."));
        if (item.isDeleted()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Report not found.");
        }
        return item;
    }

    private void checkOwnerOrAdmin(User user, Item item) {
        if (!item.getReporter().getId().equals(user.getId()) && user.getRole() != Role.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "You can only manage your own reports.");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void publish(Item item, ItemChangeKind kind) {
        events.publishEvent(new ItemChangedEvent(item.getId(), item.getVersion(), kind));
    }
}
