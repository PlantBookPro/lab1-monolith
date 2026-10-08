package com.plantarena.tournaments.application;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;


public class FixedWindowRateLimiter {

    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    private record Window(long minute, AtomicInteger count) {
    }

    public FixedWindowRateLimiter(Clock clock) {
        this.clock = clock;
    }

    
    public void check(String key, int limit) {
        long minute = clock.instant().toEpochMilli() / 60_000;
        Window window = windows.compute(key, (k, old) ->
            old == null || old.minute() != minute
                ? new Window(minute, new AtomicInteger())
                : old);
        if (window.count().incrementAndGet() > limit) {
            long retryAfterSeconds = 60 - clock.instant().getEpochSecond() % 60;
            throw new RateLimitExceededException(
                "Превышен лимит (" + limit + "/мин), повторите через " + retryAfterSeconds + " с",
                retryAfterSeconds);
        }
    }
}
