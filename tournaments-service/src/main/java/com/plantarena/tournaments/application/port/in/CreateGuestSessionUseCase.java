package com.plantarena.tournaments.application.port.in;

import java.time.Instant;


public interface CreateGuestSessionUseCase {

    GuestSessionIssued issue(String clientIp);

    record GuestSessionIssued(String token, Instant expiresAt) {
    }
}
