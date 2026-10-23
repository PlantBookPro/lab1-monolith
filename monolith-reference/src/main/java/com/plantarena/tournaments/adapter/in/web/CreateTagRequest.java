package com.plantarena.tournaments.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;


public record CreateTagRequest(@NotBlank @Size(min = 1, max = 100) String name) {
}
