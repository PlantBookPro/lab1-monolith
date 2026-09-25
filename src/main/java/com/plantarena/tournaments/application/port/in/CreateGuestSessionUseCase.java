package com.plantarena.tournaments.application.port.in;

import java.time.Instant;

/** Выдача гостевой сессии (раздел 9): публичная ручка, токен один раз. */
public interface CreateGuestSessionUseCase {

    GuestSessionIssued issue(String clientIp);

    record GuestSessionIssued(String token, Instant expiresAt) {
    }
}
