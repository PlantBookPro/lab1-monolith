package com.plantarena.tournaments.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Выходной порт tournaments: данные растения для проверки APPROVED при
 * принятии приглашения (PENDING → ACCEPTED_PENDING_MODERATION, APPROVED →
 * сразу READY). Адаптер — ACL над plants.api.PlantDirectory.
 */
public interface PlantDirectoryGateway {

    Optional<PlantSnapshot> findById(UUID plantId);

    /** Минимальный снимок растения: владелец и факт одобрения модерацией. */
    record PlantSnapshot(UUID plantId, UUID ownerId, boolean approved) {
    }
}
