package com.plantarena.tournaments.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ROUND_ELIMINATION (раздел 7): min(n − 1, max(1, floor(n · f))). */
@DisplayName("Число выбывающих из n участников при доле f")
class RoundEliminationTest {

    private final RoundElimination algorithm = new RoundElimination();

    @ParameterizedTest(name = "n={0}, f={1} → {2}")
    @CsvSource({
        "2, 0.5, 1",   // min(1, max(1, 1))
        "3, 0.5, 1",   // floor(1.5) = 1
        "4, 0.5, 2",   // floor(2.0) = 2
        "5, 0.9, 4",   // min(4, max(1, 4)) — не выбывает весь состав
        "10, 0.1, 1",  // floor(1.0) = 1
        "3, 0.34, 1",  // floor(1.02) = 1
        "7, 0.5, 3"    // floor(3.5) = 3
    })
    void число_выбывающих(int n, double fraction, int expected) {
        assertThat(algorithm.eliminatedCount(n, fraction)).isEqualTo(expected);
    }

    @Test
    @DisplayName("n < 2 и доля вне (0, 1) — ошибки аргументов")
    void некорректные_аргументы() {
        assertThatThrownBy(() -> algorithm.eliminatedCount(1, 0.5))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> algorithm.eliminatedCount(0, 0.5))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> algorithm.eliminatedCount(3, 0.0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> algorithm.eliminatedCount(3, 1.0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("фабрика выдаёт RoundElimination для ROUND_ELIMINATION")
    void фабрика() {
        assertThat(EliminationAlgorithms.forKind(EliminationAlgorithmKind.ROUND_ELIMINATION))
            .isInstanceOf(RoundElimination.class);
    }
}
