package com.plantarena.feed.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Выходной порт feed: публичные данные растения для карточки (раздел 9).
 * Адаптер — ACL над plants.api.PlantDirectory.
 */
public interface PlantCatalog {

    Optional<PlantView> findPlant(UUID plantId);

    /** Публичный минимум: asset для URL, title, жизнь (неживое не показывается). */
    record PlantView(UUID plantId, UUID assetId, String title, String lifeStatus) {
    }
}
