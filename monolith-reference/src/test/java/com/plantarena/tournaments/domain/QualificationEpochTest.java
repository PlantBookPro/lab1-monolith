package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Эпоха отбора (раздел 8, алгоритм 2): интервал и переход OPEN → CLOSED. */
@DisplayName("QualificationEpoch: открытие и закрытие эпохи")
class QualificationEpochTest {

    private static final Instant OPENS = Instant.parse("2026-09-27T10:00:00Z");
    private static final Instant CLOSES = OPENS.plusSeconds(3600);

    @Test
    @DisplayName("эпоха открывается со статусом OPEN и полуоткрытым интервалом")
    void открытие() {
        QualificationEpoch epoch = QualificationEpoch.open(UUID.randomUUID(),
            UUID.randomUUID(), 1, OPENS, CLOSES, OPENS);
        assertThat(epoch.status()).isEqualTo(EpochStatus.OPEN);
        assertThat(epoch.sequence()).isEqualTo(1);
    }

    @Test
    @DisplayName("закрытие возможно только после closesAt; повтор — ошибка состояния")
    void закрытие() {
        QualificationEpoch epoch = epoch();
        assertThatThrownBy(() -> epoch.close(CLOSES.minusNanos(1)))
            .isInstanceOf(IllegalStateException.class);
        epoch.close(CLOSES);
        assertThat(epoch.status()).isEqualTo(EpochStatus.CLOSED);
        assertThatThrownBy(() -> epoch.close(CLOSES.plusSeconds(1)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("opensAt < closesAt и sequence >= 1")
    void инварианты() {
        assertThatThrownBy(() -> QualificationEpoch.open(UUID.randomUUID(),
            UUID.randomUUID(), 0, OPENS, CLOSES, OPENS))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QualificationEpoch.open(UUID.randomUUID(),
            UUID.randomUUID(), 1, CLOSES, OPENS, OPENS))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private QualificationEpoch epoch() {
        return QualificationEpoch.open(UUID.randomUUID(), UUID.randomUUID(), 1,
            OPENS, CLOSES, OPENS);
    }
}
