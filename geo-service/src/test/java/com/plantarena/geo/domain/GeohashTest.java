package com.plantarena.geo.domain;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Geohash (раздел 8): канонический алгоритм base32, эталонные векторы. */
@DisplayName("Geohash: кодирование координат в ячейку сетки")
class GeohashTest {

    static Stream<Arguments> vectors() {
        return Stream.of(
            Arguments.arguments(57.64911, 10.40744, 11, "u4pruydqqvj"),
            Arguments.arguments(-25.382708, -49.585507, 8, "6gkxxehz"),
            Arguments.arguments(0.0, 0.0, 4, "s000"),
            Arguments.arguments(-90.0, -180.0, 4, "0000"),
            Arguments.arguments(90.0, 180.0, 4, "zzzz"));
    }

    @ParameterizedTest(name = "{0},{1} p={2} → {3}")
    @MethodSource("vectors")
    void эталонные_векторы(double latitude, double longitude, int precision, String expected) {
        assertThat(Geohash.encode(latitude, longitude, precision)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 13})
    void точность_вне_диапазона(int precision) {
        assertThatThrownBy(() -> Geohash.encode(10, 10, precision))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("координаты вне диапазона — ошибка (границы включаются)")
    void координаты_вне_диапазона() {
        assertThatThrownBy(() -> Geohash.encode(90.0001, 0, 4))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Geohash.encode(0, -180.0001, 4))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("префикс ячейки меньшей точности — ячейка большей точности (вложенность сетки)")
    void вложенность_точностей() {
        String coarse = Geohash.encode(55.7558, 37.6173, 3);
        String fine = Geohash.encode(55.7558, 37.6173, 6);
        assertThat(fine).startsWith(coarse);
    }
}
