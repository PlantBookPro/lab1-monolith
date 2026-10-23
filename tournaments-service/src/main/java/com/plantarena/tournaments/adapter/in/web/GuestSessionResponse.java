package com.plantarena.tournaments.adapter.in.web;

import java.time.Instant;


public record GuestSessionResponse(String token, Instant expiresAt) {
}
