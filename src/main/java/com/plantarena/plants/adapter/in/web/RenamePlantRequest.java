package com.plantarena.plants.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Тело PATCH /plants/{id}: только title (asset/owner менять нельзя, раздел 13). */
public record RenamePlantRequest(@NotBlank @Size(min = 1, max = 100) String title) {
}
