package com.plantarena.tournaments.domain;

import java.util.Optional;
import java.util.UUID;

/** Порт репозитория агрегата GuestSession (раздел 9). */
public interface GuestSessionRepository {

    GuestSession save(GuestSession session);

    /** Сессия по хэшу токена (разрешение гостя по заголовку X-Guest-Token). */
    Optional<GuestSession> findByTokenHash(String tokenHash);
}
