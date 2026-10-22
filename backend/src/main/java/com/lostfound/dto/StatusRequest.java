package com.lostfound.dto;

import com.lostfound.model.ItemStatus;
import jakarta.validation.constraints.NotNull;

public record StatusRequest(
        @NotNull(message = "Status is required")
        ItemStatus status
) {
}
