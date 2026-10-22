package com.lostfound.contract;

/** Immutable committed change event; never carries entities or private fields. */
public record ItemChangedEvent(Long itemId, long version, ItemChangeKind kind) {
}
