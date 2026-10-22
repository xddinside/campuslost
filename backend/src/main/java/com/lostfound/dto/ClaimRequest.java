package com.lostfound.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ClaimRequest(
        @NotBlank(message = "Message is required")
        @Size(max = 2000, message = "Message must be at most 2000 characters")
        String message,

        Long lostReportId,

        @Size(max = 2000, message = "Ownership answer must be at most 2000 characters")
        String ownershipAnswer
) {
}
