package com.plantarena.identity.domain;

/**
 * Роль учётной записи (раздел 2). USER присутствует всегда (инвариант агрегата User).
 */
public enum UserRole {
    USER, MODERATOR, ADMIN
}
