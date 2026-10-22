package com.lostfound.handover;

/** Injected so tests can supply deterministic codes, including leading zeros. */
public interface HandoverCodeGenerator {
    String nextCode();
}
