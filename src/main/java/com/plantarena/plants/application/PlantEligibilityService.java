package com.plantarena.plants.application;

import com.plantarena.plants.api.PlantEligibility;
import com.plantarena.plants.api.PlantNotEligibleException;
import com.plantarena.plants.api.PlantNotFoundException;
import com.plantarena.plants.api.ReservationConflictException;
import com.plantarena.plants.domain.ImageRestriction;
import com.plantarena.plants.domain.ImageRestrictionRepository;
import com.plantarena.plants.domain.ImageReusePolicy;
import com.plantarena.plants.domain.LifeStatus;
import com.plantarena.plants.domain.ModerationStatus;
import com.plantarena.plants.domain.Plant;
import com.plantarena.plants.domain.PlantRepository;
import com.plantarena.plants.domain.PlantReservation;
import com.plantarena.plants.domain.PlantReservationRepository;
import com.plantarena.plants.domain.ReservationStatus;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация опубликованного контракта PlantEligibility (раздел 6): проверки
 * и изменение резерва атомарны. Гонку двух одновременных резервов одной пары
 * (ownerId, fingerprint) закрывает частичный уникальный индекс PostgreSQL —
 * нарушение переводится в ReservationConflictException (допущение 4).
 */
@Service
public class PlantEligibilityService implements PlantEligibility {

    private final PlantRepository plants;
    private final ImageRestrictionRepository restrictions;
    private final PlantReservationRepository reservations;
    private final ImageReusePolicy reusePolicy = new ImageReusePolicy();
    private final Clock clock;

    public PlantEligibilityService(PlantRepository plants,
                                   ImageRestrictionRepository restrictions,
                                   PlantReservationRepository reservations, Clock clock) {
        this.plants = plants;
        this.restrictions = restrictions;
        this.reservations = reservations;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID reserveSubmission(UUID ownerId, UUID plantId, UUID idempotencyKey) {
        PlantReservation existing = reservations.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            return existing.id(); // идемпотентный повтор команды (раздел 6)
        }
        Plant plant = plants.findById(plantId)
            .orElseThrow(() -> new PlantNotFoundException("Растение не найдено: " + plantId));
        if (!plant.ownerId().equals(ownerId)) {
            throw new PlantNotEligibleException(PlantNotEligibleException.Reason.NOT_OWNER,
                "Растение принадлежит другому владельцу");
        }
        if (plant.lifeStatus() != LifeStatus.ALIVE) {
            throw new PlantNotEligibleException(PlantNotEligibleException.Reason.DEAD,
                "Растение погибло и не может участвовать");
        }
        if (plant.moderationStatus() == ModerationStatus.REJECTED) {
            throw new PlantNotEligibleException(PlantNotEligibleException.Reason.NOT_RESERVABLE,
                "Отклонённая заявка не резервируется");
        }
        requireNotRestricted(ownerId, plant);

        reservations.findActiveByOwnerAndFingerprint(ownerId, plant.fingerprint().value())
            .ifPresent(active -> {
                throw new ReservationConflictException(
                    "Изображение уже зарезервировано заявкой: " + active.plantId());
            });
        try {
            PlantReservation saved = reservations.save(PlantReservation.reserve(
                ownerId, plant.id(), plant.fingerprint(), idempotencyKey, clock.instant()));
            return saved.id();
        } catch (DataIntegrityViolationException e) {
            // гонка: частичный уникальный индекс (owner_id, fingerprint) WHERE ACTIVE
            throw new ReservationConflictException(
                "Изображение уже зарезервировано другой заявкой (гонка)");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void confirmEligibility(UUID ownerId, UUID plantId, UUID reservationId) {
        Plant plant = plants.findById(plantId)
            .orElseThrow(() -> new PlantNotFoundException("Растение не найдено: " + plantId));
        if (!plant.ownerId().equals(ownerId)) {
            throw new PlantNotEligibleException(PlantNotEligibleException.Reason.NOT_OWNER,
                "Растение принадлежит другому владельцу");
        }
        if (plant.lifeStatus() != LifeStatus.ALIVE) {
            throw new PlantNotEligibleException(PlantNotEligibleException.Reason.DEAD,
                "Растение погибло и не может участвовать");
        }
        if (plant.moderationStatus() != ModerationStatus.APPROVED) {
            throw new PlantNotEligibleException(PlantNotEligibleException.Reason.NOT_APPROVED,
                "К старту допускается только одобренное растение (раздел 6)");
        }
        PlantReservation reservation = reservations.findById(reservationId).orElse(null);
        if (reservation == null
                || reservation.status() != ReservationStatus.ACTIVE
                || !reservation.plantId().equals(plantId)
                || !reservation.ownerId().equals(ownerId)
                || !reservation.fingerprint().equals(plant.fingerprint())) {
            throw new PlantNotEligibleException(
                PlantNotEligibleException.Reason.NO_ACTIVE_RESERVATION,
                "Активный резерв этой заявки не найден");
        }
    }

    @Override
    @Transactional
    public void releaseReservation(UUID reservationId) {
        reservations.findById(reservationId).ifPresent(reservation -> {
            if (reservation.release(clock.instant())) {
                reservations.save(reservation);
            }
        });
    }

    private void requireNotRestricted(UUID ownerId, Plant plant) {
        List<ImageRestriction> history = restrictions.findByOwnerAndFingerprint(
            ownerId, plant.fingerprint().value());
        reusePolicy.activeRestriction(history, clock.instant()).ifPresent(restriction -> {
            throw new PlantNotEligibleException(PlantNotEligibleException.Reason.RESTRICTED,
                "Изображение запрещено: " + restriction.kind(), restriction.expiresAt());
        });
    }
}
