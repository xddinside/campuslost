package com.lostfound.handover;

import com.lostfound.exception.ApiException;
import org.springframework.http.HttpStatus;

/** 429 carrying the Retry-After hint required by the handover contract. */
public class HandoverRateLimitException extends ApiException {

    private final long retryAfterSeconds;

    public HandoverRateLimitException(String code, String message, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, code, message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
