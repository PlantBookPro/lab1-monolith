package com.plantarena.tournaments.adapter.in.web;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;


public record SubmitGlobalEntryRequest(@NotNull UUID plantId) {
}
