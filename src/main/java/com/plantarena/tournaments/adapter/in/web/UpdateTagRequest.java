package com.plantarena.tournaments.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Тело PATCH /tags/{id} (раздел 13). */
public record UpdateTagRequest(@NotBlank @Size(min = 1, max = 100) String name) {
}
