package com.lostfound.handover;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record HandoverVerifyRequest(
        @NotBlank(message = "Enter the six-digit pickup code.")
        @Pattern(regexp = "\\d{6}", message = "Enter the six-digit pickup code.")
        String code
) {
}
