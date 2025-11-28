package com.plantarena.plants.application.support;

import com.plantarena.plants.domain.PlantReservation;
import com.plantarena.plants.domain.PlantReservationRepository;
import com.plantarena.plants.domain.ReservationStatus;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * In-memory фейк, честный к set-инварианту (контрактные тесты): активный резерв
 * один на пару (ownerId, fingerprint) — как частичный уникальный индекс БД.
 */
public class InMemoryPlantReservationRepository implements PlantReservationRepository {

    public final Map<UUID, PlantReservation> reservations = new ConcurrentHashMap<>();

    @Override
    public PlantReservation save(PlantReservation reservation) {
        reservations.values().stream()
            .filter(existing -> existing.status() == ReservationStatus.ACTIVE
                && existing.ownerId().equals(reservation.ownerId())
                && existing.fingerprint().value().equals(reservation.fingerprint().value())
                && !existing.id().equals(reservation.id()))
            .findAny()
            .ifPresent(existing -> {
                throw new DataIntegrityViolationException(
                    "Активный резерв пары уже существует: " + existing.id());
            });
        reservations.put(reservation.id(), reservation);
        return reservation;
    }

    @Override
    public Optional<PlantReservation> findById(UUID id) {
        return Optional.ofNullable(reservations.get(id));
    }

    @Override
    public Optional<PlantReservation> findByIdempotencyKey(UUID idempotencyKey) {
        return reservations.values().stream()
            .filter(reservation -> reservation.idempotencyKey().equals(idempotencyKey))
            .findFirst();
    }

    @Override
    public Optional<PlantReservation> findActiveByOwnerAndFingerprint(UUID ownerId,
                                                                      String fingerprintValue) {
        return reservations.values().stream()
            .filter(reservation -> reservation.status() == ReservationStatus.ACTIVE
                && reservation.ownerId().equals(ownerId)
                && reservation.fingerprint().value().equals(fingerprintValue))
            .findFirst();
    }

    @Override
    public Optional<PlantReservation> findActiveByPlantId(UUID plantId) {
        return reservations.values().stream()
            .filter(reservation -> reservation.status() == ReservationStatus.ACTIVE
                && reservation.plantId().equals(plantId))
            .findFirst();
    }

    public List<PlantReservation> sorted() {
        return reservations.values().stream()
            .sorted(Comparator.comparing(PlantReservation::id))
            .toList();
    }
}
