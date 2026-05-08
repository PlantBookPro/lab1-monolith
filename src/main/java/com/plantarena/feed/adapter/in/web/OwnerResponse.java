package com.plantarena.feed.adapter.in.web;

import java.util.UUID;

/** Публичный минимум о владельце карточки (раздел 9: не приватный профиль). */
public record OwnerResponse(UUID userId, String displayName) {
}
