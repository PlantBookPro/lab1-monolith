package com.plantarena.plants.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Порт репозитория агрегата Plant (раздел 5): интерфейс в domain,
 * реализация в adapter.out.persistence. Список отсортирован по id —
 * детерминированная пагинация с tie-break (раздел 13).
 */
public interface PlantRepository {

    Plant save(Plant plant);

    Optional<Plant> findById(UUID id);

    /** Неархивированное растение на asset: один живой Plant на файл (ADR-008). */
    Optional<Plant> findActiveByAssetId(UUID assetId);

    /** Все неархивированные растения владельца (владельцу и админу). */
    List<Plant> findByOwner(UUID ownerId, int offset, int limit);

    long countByOwner(UUID ownerId);

    /** Публичные для остальных: APPROVED и не архивированы (ADR-008). */
    List<Plant> findApprovedByOwner(UUID ownerId, int offset, int limit);

    long countApprovedByOwner(UUID ownerId);
}
