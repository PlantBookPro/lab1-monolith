package com.plantarena.tournaments.adapter.in.web;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Тело POST /tournaments/{id}/invitations (раздел 13). */
public record InviteUserRequest(@NotNull UUID userId) {
}
