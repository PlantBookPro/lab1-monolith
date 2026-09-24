package com.plantarena.identity.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Вход DTO изменения профиля: только displayName; произвольные roles/status
 * запрещены (раздел 13). Неизвестные поля JSON игнорируются.
 */
public record UpdateUserRequest(
        @NotBlank @Size(max = 100) String displayName) {
}
