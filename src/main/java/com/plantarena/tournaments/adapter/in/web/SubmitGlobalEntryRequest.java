package com.plantarena.tournaments.adapter.in.web;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Вход DTO подачи глобальной заявки (раздел 13). */
public record SubmitGlobalEntryRequest(@NotNull UUID plantId) {
}
