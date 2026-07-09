package com.plantarena.plants.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data для plant_reservation; findFirst — страховка от дублей. */
public interface PlantReservationJpaRepository extends JpaRepository<PlantReservationJpaEntity, UUID> {

    Optional<PlantReservationJpaEntity> findByIdempotencyKey(UUID idempotencyKey);

    Optional<PlantReservationJpaEntity> findFirstByOwnerIdAndFingerprintAndStatus(
        UUID ownerId, String fingerprint, String status);

    Optional<PlantReservationJpaEntity> findFirstByPlantIdAndStatus(UUID plantId, String status);
}
