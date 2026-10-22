package com.lostfound.service;

import com.lostfound.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

public final class ItemRules {

    public static final List<String> CATEGORIES = List.of(
            "Electronics",
            "Clothing",
            "Books",
            "Keys",
            "Wallet & Purses",
            "ID & Documents",
            "Jewelry",
            "Bags",
            "Other"
    );

    private ItemRules() {
    }

    public static String category(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CATEGORY", "Category is required.");
        }
        String trimmed = value.trim();
        return CATEGORIES.stream()
                .filter(allowed -> allowed.toLowerCase(Locale.ROOT).equals(trimmed.toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CATEGORY",
                        "Choose one of the supported categories."));
    }

    public static Instant occurredAt(Instant value) {
        if (value == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE", "Date is required.");
        }
        if (value.isAfter(Instant.now().plus(1, ChronoUnit.DAYS))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE",
                    "The date cannot be in the future.");
        }
        if (value.isBefore(Instant.parse("2000-01-01T00:00:00Z"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE",
                    "Choose a realistic date.");
        }
        return value;
    }

    public static String requiredText(String value, String fieldCode, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, fieldCode, message);
        }
        return value.trim();
    }

    public static String question(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUESTION",
                    "Ownership question must be at most 200 characters.");
        }
        return trimmed;
    }
}
