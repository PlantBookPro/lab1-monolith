package com.plantarena.identity.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;


public record UpdateUserRequest(
        @NotBlank @Size(max = 100) String displayName) {
}
