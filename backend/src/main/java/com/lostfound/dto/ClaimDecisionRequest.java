package com.lostfound.dto;

import com.lostfound.model.ClaimStatus;
import jakarta.validation.constraints.NotNull;

public record ClaimDecisionRequest(
        @NotNull(message = "Decision is required")
        ClaimStatus status
) {
}
