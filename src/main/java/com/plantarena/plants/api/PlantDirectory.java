package com.plantarena.plants.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Опубликованный read-контракт plants для tournaments (раздел 4.3): данные
 * растения по id. Права не проверяются — внутренний контракт монолита
 * (как MediaAssets.loadContent для moderation, итерация 4): вызов идёт по
 * plantId из приглашения, видимость решает tournaments.
 */
public interface PlantDirectory {

    Optional<PlantData> findById(UUID plantId);
}
