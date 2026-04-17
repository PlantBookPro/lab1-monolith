package com.plantarena.tournaments.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-контракт tournaments для feed (раздел 9): разрешение гостевого
 * токена ленты. Реализация — фасад application (GuestSessionDirectoryFacade).
 */
public interface GuestSessionDirectory {

    /** Активная сессия по сырому токену: проверяет хэш и срок действия. */
    Optional<UUID> activeSessionId(String rawToken);
}
