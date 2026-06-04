package com.plantarena.tournaments.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Тело POST /tags (раздел 13). */
public record CreateTagRequest(@NotBlank @Size(min = 1, max = 100) String name) {
}
