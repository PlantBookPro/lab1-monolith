package com.plantarena.tournaments.application;

/**
 * Превышен лимит защиты от накрутки (раздел 9): 429 RATE_LIMITED +
 * заголовок Retry-After (секунды до конца фиксированного окна).
 */
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
