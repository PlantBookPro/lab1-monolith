package com.plantarena.identity.adapter.in.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Вход DTO создания пользователя: пароль разрешён (ADR-005), роли и статус
 * передавать нельзя — полей в DTO нет (раздел 2 требований).
 */
public record CreateUserRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 8, max = 100) String password,
        @NotBlank @Size(max = 100) String displayName) {
}
