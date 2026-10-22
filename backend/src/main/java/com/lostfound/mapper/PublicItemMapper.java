package com.lostfound.mapper;

import com.lostfound.dto.ItemResponse;
import com.lostfound.model.ClaimStatus;
import com.lostfound.model.Item;
import com.lostfound.photo.PhotoStorageService;
import com.lostfound.repository.ClaimRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The single public item serialization path. Keeping contact and private
 * evidence fields out of this class makes it hard to leak them by accident.
 */
@Component
public class PublicItemMapper {

    private final ClaimRepository claimRepository;
    private final PhotoStorageService photoStorageService;

    public PublicItemMapper(ClaimRepository claimRepository, PhotoStorageService photoStorageService) {
        this.claimRepository = claimRepository;
        this.photoStorageService = photoStorageService;
    }

    public ItemResponse toResponse(Item item) {
        long pending = claimRepository.countByItemIdAndStatusIn(item.getId(),
                List.of(ClaimStatus.PENDING));
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
                item.getCreatedAt(),
                item.getUpdatedAt(),
                item.getResolvedAt(),
                item.getVersion(),
                photoStorageService.url(item.getPhotoId()),
                pending
        );
    }
}
