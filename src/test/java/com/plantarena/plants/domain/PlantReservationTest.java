package com.plantarena.plants.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Агрегат PlantReservation: ACTIVE → RELEASED, идемпотентное освобождение")
class PlantReservationTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    private PlantReservation newReservation() {
        return PlantReservation.reserve(UUID.randomUUID(), UUID.randomUUID(),
            new ImageFingerprint("d".repeat(64), 1), UUID.randomUUID(), NOW);
    }

    @Test
    void резерв_создаётся_активным_с_ключом_команды() {
        UUID key = UUID.randomUUID();
        PlantReservation reservation = PlantReservation.reserve(
            UUID.randomUUID(), UUID.randomUUID(),
            new ImageFingerprint("d".repeat(64), 1), key, NOW);

        assertThat(reservation.status()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(reservation.idempotencyKey()).isEqualTo(key);
        assertThat(reservation.releasedAt()).isNull();
    }

    @Test
    void освобождение_фиксирует_момент_и_идемпотентно() {
        PlantReservation reservation = newReservation();

        assertThat(reservation.release(NOW.plusSeconds(60))).isTrue();
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(reservation.releasedAt()).isEqualTo(NOW.plusSeconds(60));

        assertThat(reservation.release(NOW.plusSeconds(120))).isFalse();
        assertThat(reservation.releasedAt()).isEqualTo(NOW.plusSeconds(60));
    }
}
