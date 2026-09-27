package com.plantarena.tournaments.adapter.in.web;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Тело POST /invitations/{id}/accept (раздел 13): своё растение. */
public record AcceptInvitationRequest(@NotNull UUID plantId) {
}
