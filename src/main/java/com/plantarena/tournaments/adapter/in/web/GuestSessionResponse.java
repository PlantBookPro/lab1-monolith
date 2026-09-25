package com.plantarena.tournaments.adapter.in.web;

import java.time.Instant;

/** Ответ POST /guest-sessions: токен возвращается ровно один раз (раздел 9). */
public record GuestSessionResponse(String token, Instant expiresAt) {
}
