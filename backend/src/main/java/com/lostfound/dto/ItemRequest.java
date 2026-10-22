package com.lostfound.dto;

import com.lostfound.model.ItemType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record ItemRequest(
        @NotNull(message = "Type is required")
        ItemType type,

        @NotBlank(message = "Title is required")
        @Size(max = 120, message = "Title must be at most 120 characters")
        String title,

        @NotBlank(message = "Description is required")
        @Size(max = 4000, message = "Description must be at most 4000 characters")
        String description,

        @NotBlank(message = "Category is required")
        @Size(max = 60, message = "Category must be at most 60 characters")
        String category,

        @NotBlank(message = "Location is required")
        @Size(max = 120, message = "Location must be at most 120 characters")
        String location,

        @NotNull(message = "Date is required")
        Instant dateOccurred,

        @Size(max = 200, message = "Ownership question must be at most 200 characters")
        String ownershipQuestion,

        @Size(max = 36, message = "Photo reference is invalid")
        String photoId
) {
}
