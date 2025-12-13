package com.plantarena.feed.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Выходной порт feed: минимальные публичные сведения о владельце (раздел 9:
 * не приватный профиль, email и координаты). Адаптер — ACL над
 * identity.api.UserDirectory.
 */
public interface OwnerDirectory {

    Optional<OwnerView> findOwner(UUID userId);

    record OwnerView(UUID userId, String displayName) {
    }
}
