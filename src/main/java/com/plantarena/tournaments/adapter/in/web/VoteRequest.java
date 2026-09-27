package com.plantarena.tournaments.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Тело PUT vote (раздел 9). */
public record VoteRequest(
        @NotBlank
        @Pattern(regexp = "LIKE|DISLIKE", message = "допустимо только LIKE или DISLIKE")
        String value) {
}
