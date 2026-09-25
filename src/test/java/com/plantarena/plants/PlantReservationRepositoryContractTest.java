package com.plantarena.plants;

import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.PlantReservation;
import com.plantarena.plants.domain.PlantReservationRepository;
import com.plantarena.plants.domain.ReservationStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Контракт PlantReservationRepository (раздел 14.2): честность фейка —
 * set-инвариант «один активный резерв на пару» обеспечивает и фейк, и БД.
 * @Transactional обязателен на базовом классе (урок итерации 2, фикс 56e8e3a).
 */
@DisplayName("Контракт PlantReservationRepository")
@Transactional
public abstract class PlantReservationRepositoryContractTest {

    protected static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    protected static final ImageFingerprint FINGERPRINT = new ImageFingerprint("c".repeat(64), 1);

    protected abstract PlantReservationRepository repository();

    private PlantReservation activeReservation(UUID ownerId, UUID plantId, UUID key) {
        return PlantReservation.reserve(ownerId, plantId, FINGERPRINT, key, NOW);
    }

    @Test
    @DisplayName("резерв сохраняется и находится по id и ключу идемпотентности")
    void резерв_сохраняется_и_находится_по_id_и_ключу() {
        UUID ownerId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        PlantReservation reservation = activeReservation(ownerId, UUID.randomUUID(), key);
        repository().save(reservation);

        assertThat(repository().findById(reservation.id())).isPresent();
        assertThat(repository().findByIdempotencyKey(key)).isPresent();
        assertThat(repository().findByIdempotencyKey(UUID.randomUUID())).isEmpty();
        assertThat(repository().findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("активный резерв находится по паре и по растению")
    void активный_резерв_находится_по_паре_и_растению() {
        UUID ownerId = UUID.randomUUID();
        UUID plantId = UUID.randomUUID();
        repository().save(activeReservation(ownerId, plantId, UUID.randomUUID()));

        assertThat(repository().findActiveByOwnerAndFingerprint(ownerId, FINGERPRINT.value()))
            .isPresent();
        assertThat(repository().findActiveByPlantId(plantId)).isPresent();
        assertThat(repository().findActiveByPlantId(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("второй активный резерв пары отклоняется (допущение 4)")
    void второй_активный_резерв_пары_отклоняется() {
        UUID ownerId = UUID.randomUUID();
        repository().save(activeReservation(ownerId, UUID.randomUUID(), UUID.randomUUID()));

        assertThatThrownBy(() -> repository()
            .save(activeReservation(ownerId, UUID.randomUUID(), UUID.randomUUID())))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("освобождение убирает из активных и позволяет новый резерв пары")
    void освобождение_убирает_из_активных_и_позволяет_новый() {
        UUID ownerId = UUID.randomUUID();
        UUID plantId = UUID.randomUUID();
        PlantReservation reservation = activeReservation(ownerId, plantId, UUID.randomUUID());
        repository().save(reservation);

        reservation.release(NOW.plusSeconds(60));
        repository().save(reservation);

        assertThat(repository().findActiveByOwnerAndFingerprint(ownerId, FINGERPRINT.value()))
            .isEmpty();
        assertThat(repository().findActiveByPlantId(plantId)).isEmpty();

        PlantReservation released = repository().findById(reservation.id()).orElseThrow();
        assertThat(released.status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(released.releasedAt()).isEqualTo(NOW.plusSeconds(60));

        repository().save(activeReservation(ownerId, UUID.randomUUID(), UUID.randomUUID()));
    }
}
