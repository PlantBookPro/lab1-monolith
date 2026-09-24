package com.plantarena.identity.adapter.in.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * Вход DTO координат: диапазоны дублируют доменную проверку GeoPoint
 * (Bean Validation — на request DTO, раздел 17).
 */
public record UpdateLocationRequest(
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude) {
}
