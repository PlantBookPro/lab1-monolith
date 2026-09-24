package com.plantarena.shared.security;

/**
 * Технический код роли для CurrentActor (раздел 2 требований).
 * Доменная роль identity (identity.domain.UserRole) маппится в него адаптером.
 */
public enum AppRole {
    USER, MODERATOR, ADMIN
}
