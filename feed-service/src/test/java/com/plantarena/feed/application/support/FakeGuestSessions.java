package com.plantarena.feed.application.support;

import com.plantarena.feed.application.port.out.GuestSessions;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Фейк read-порта гостевых сессий для тестов FeedService. */
public class FakeGuestSessions implements GuestSessions {

    public final Map<String, UUID> activeByToken = new HashMap<>();

    @Override
    public Optional<UUID> activeSessionId(String rawToken) {
        return Optional.ofNullable(activeByToken.get(rawToken));
    }
}
