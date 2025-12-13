package com.plantarena.feed.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Выходной порт feed: гостевые сессии tournaments (раздел 9). Адаптер —
 * ACL над tournaments.api.GuestSessionDirectory.
 */
public interface GuestSessions {

    /** Активная сессия по сырому токену: проверяет хэш и срок действия. */
    Optional<UUID> activeSessionId(String rawToken);
}
