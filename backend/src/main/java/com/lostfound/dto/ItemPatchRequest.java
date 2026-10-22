package com.lostfound.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.lostfound.model.ItemType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Partial item edit. Jackson setters mark fields explicitly present, so an
 * omitted field preserves the stored value while an explicit JSON null clears
 * nullable fields (photo and ownership question).
 */
public class ItemPatchRequest {

    @NotNull(message = "Version is required")
    @Min(value = 0, message = "Version is invalid")
    private Long version;

    private ItemType type;
    private String title;
    private String description;
    private String category;
    private String location;
    private Instant dateOccurred;
    private String ownershipQuestion;
    private String photoId;

    private boolean typePresent;
    private boolean titlePresent;
    private boolean descriptionPresent;
    private boolean categoryPresent;
    private boolean locationPresent;
    private boolean dateOccurredPresent;
    private boolean ownershipQuestionPresent;
    private boolean photoIdPresent;

    public Long getVersion() {
        return version;
    }

    @JsonSetter("version")
    public void setVersion(Long version) {
        this.version = version;
    }

    public ItemType getType() {
        return type;
    }

    @JsonSetter("type")
    public void setType(ItemType type) {
        this.type = type;
        this.typePresent = true;
    }

    public String getTitle() {
        return title;
    }

    @JsonSetter("title")
    public void setTitle(@Size(max = 120, message = "Title must be at most 120 characters") String title) {
        this.title = title;
        this.titlePresent = true;
    }

    public String getDescription() {
        return description;
    }

    @JsonSetter("description")
    public void setDescription(@Size(max = 4000, message = "Description must be at most 4000 characters") String description) {
        this.description = description;
        this.descriptionPresent = true;
    }

    public String getCategory() {
        return category;
    }

    @JsonSetter("category")
    public void setCategory(@Size(max = 60, message = "Category must be at most 60 characters") String category) {
        this.category = category;
        this.categoryPresent = true;
    }

    public String getLocation() {
        return location;
    }

    @JsonSetter("location")
    public void setLocation(@Size(max = 120, message = "Location must be at most 120 characters") String location) {
        this.location = location;
        this.locationPresent = true;
    }

    public Instant getDateOccurred() {
        return dateOccurred;
    }

    @JsonSetter("dateOccurred")
    public void setDateOccurred(Instant dateOccurred) {
        this.dateOccurred = dateOccurred;
        this.dateOccurredPresent = true;
    }

    public String getOwnershipQuestion() {
        return ownershipQuestion;
    }

    @JsonSetter("ownershipQuestion")
    public void setOwnershipQuestion(@Size(max = 200, message = "Ownership question must be at most 200 characters") String ownershipQuestion) {
        this.ownershipQuestion = ownershipQuestion;
        this.ownershipQuestionPresent = true;
    }

    public String getPhotoId() {
        return photoId;
    }

    @JsonSetter("photoId")
    public void setPhotoId(@Size(max = 36, message = "Photo reference is invalid") String photoId) {
        this.photoId = photoId;
        this.photoIdPresent = true;
    }

    public boolean hasType() { return typePresent; }
    public boolean hasTitle() { return titlePresent; }
    public boolean hasDescription() { return descriptionPresent; }
    public boolean hasCategory() { return categoryPresent; }
    public boolean hasLocation() { return locationPresent; }
    public boolean hasDateOccurred() { return dateOccurredPresent; }
    public boolean hasOwnershipQuestion() { return ownershipQuestionPresent; }
    public boolean hasPhotoId() { return photoIdPresent; }
}
