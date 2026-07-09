package com.plantarena.plants.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Тело POST /plants (раздел 13). */
public record SubmitPlantRequest(
        @NotNull UUID assetId,
        @NotBlank @Size(min = 1, max = 100) String title) {
}
