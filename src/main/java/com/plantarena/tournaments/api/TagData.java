package com.plantarena.tournaments.api;

import java.util.UUID;

/** Опубликованные данные тега (справочник тематик, раздел 13). */
public record TagData(UUID id, String name) {
}
