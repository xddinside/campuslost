package com.lostfound.dto;

import jakarta.validation.constraints.Size;

public record ReleaseRequest(
        @Size(max = 500, message = "Release reason must be at most 500 characters")
        String reason
) {
}
