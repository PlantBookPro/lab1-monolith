package com.plantarena.identity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("VO GeoPoint: допустимые диапазоны координат")
class GeoPointTest {

    @ParameterizedTest
    @ValueSource(doubles = {-90.0001, 90.0001, Double.NaN, Double.POSITIVE_INFINITY})
    void широта_вне_диапазона_отклоняется(double latitude) {
        assertThatThrownBy(() -> new GeoPoint(latitude, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-180.0001, 180.0001, Double.NaN})
    void долгота_вне_диапазона_отклоняется(double longitude) {
        assertThatThrownBy(() -> new GeoPoint(0, longitude))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void граничные_координаты_допустимы() {
        assertThat(new GeoPoint(-90, -180)).isNotNull();
        assertThat(new GeoPoint(90, 180)).isNotNull();
    }
}
