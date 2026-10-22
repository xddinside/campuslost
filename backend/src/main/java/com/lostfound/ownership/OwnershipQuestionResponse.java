package com.lostfound.ownership;

/** Private ownership prompt shown to signed-in claimants. */
public record OwnershipQuestionResponse(Long itemId, String question, boolean required) {
}
