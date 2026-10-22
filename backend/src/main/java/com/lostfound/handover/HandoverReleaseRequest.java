package com.lostfound.handover;

import jakarta.validation.constraints.Size;

public record HandoverReleaseRequest(
        @Size(max = 500, message = "Release reason must be at most 500 characters.")
        String reason
) {
}
