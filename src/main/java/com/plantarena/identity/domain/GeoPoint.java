package com.plantarena.identity.domain;

/**
 * VO «Координаты»: широта [-90, 90], долгота [-180, 180], границы включаются.
 */
public record GeoPoint(double latitude, double longitude) {

    public GeoPoint {
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("Широта вне диапазона [-90, 90]: " + latitude);
        }
        if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Долгота вне диапазона [-180, 180]: " + longitude);
        }
    }
}
