package com.plantarena.plants.adapter.out.persistence;

import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.PlantReservation;
import com.plantarena.plants.domain.PlantReservationRepository;
import com.plantarena.plants.domain.ReservationStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация порта PlantReservationRepository на JPA + PostgreSQL (раздел 14.2).
 * find-or-create + update + saveAndFlush; set-инвариант «один активный резерв
 * на пару» — частичный уникальный индекс plant_reservation_active_pair_uidx.
 */
@Repository
@Transactional
public class JpaPlantReservationRepository implements PlantReservationRepository {

    private final PlantReservationJpaRepository reservations;

    public JpaPlantReservationRepository(PlantReservationJpaRepository reservations) {
        this.reservations = reservations;
    }

    @Override
    public PlantReservation save(PlantReservation reservation) {
        PlantReservationJpaEntity entity = reservations.findById(reservation.id())
            .orElseGet(() -> new PlantReservationJpaEntity(reservation.id(),
                reservation.ownerId(), reservation.plantId(), reservation.fingerprint().value(),
                reservation.fingerprint().algorithmVersion(), reservation.idempotencyKey(),
                reservation.createdAt()));
        entity.update(reservation.status().name(), reservation.releasedAt());
        return toDomain(reservations.saveAndFlush(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlantReservation> findById(UUID id) {
        return reservations.findById(id).map(JpaPlantReservationRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlantReservation> findByIdempotencyKey(UUID idempotencyKey) {
        return reservations.findByIdempotencyKey(idempotencyKey)
            .map(JpaPlantReservationRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlantReservation> findActiveByOwnerAndFingerprint(UUID ownerId,
                                                                      String fingerprintValue) {
        return reservations.findFirstByOwnerIdAndFingerprintAndStatus(ownerId,
                fingerprintValue, ReservationStatus.ACTIVE.name())
            .map(JpaPlantReservationRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlantReservation> findActiveByPlantId(UUID plantId) {
        return reservations.findFirstByPlantIdAndStatus(plantId, ReservationStatus.ACTIVE.name())
            .map(JpaPlantReservationRepository::toDomain);
    }

    private static PlantReservation toDomain(PlantReservationJpaEntity entity) {
        return PlantReservation.restore(entity.getId(), entity.getOwnerId(), entity.getPlantId(),
            new ImageFingerprint(entity.getFingerprint(), entity.getFingerprintVersion()),
            entity.getIdempotencyKey(), ReservationStatus.valueOf(entity.getStatus()),
            entity.getCreatedAt(), entity.getReleasedAt());
    }
}
