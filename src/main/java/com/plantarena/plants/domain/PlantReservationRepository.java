package com.plantarena.plants.domain;

import java.util.Optional;
import java.util.UUID;

/** Порт репозитория резервов изображений (раздел 6). */
public interface PlantReservationRepository {

    PlantReservation save(PlantReservation reservation);

    Optional<PlantReservation> findById(UUID id);

    /** Идемпотентный повтор команды резервирования (по ключу команды). */
    Optional<PlantReservation> findByIdempotencyKey(UUID idempotencyKey);

    /** Активный резерв пары (ownerId, fingerprint) — не более одного (допущение 4). */
    Optional<PlantReservation> findActiveByOwnerAndFingerprint(UUID ownerId, String fingerprintValue);

    /** Активный резерв конкретной заявки (для запрета архивации, раздел 13). */
    Optional<PlantReservation> findActiveByPlantId(UUID plantId);
}
