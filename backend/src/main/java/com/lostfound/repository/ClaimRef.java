package com.lostfound.repository;

/**
 * Scalar claim reference used to discover lock targets without loading the
 * claim entity into the persistence context before its pessimistic lock.
 */
public record ClaimRef(Long itemId, Long lostReportId) {
}
