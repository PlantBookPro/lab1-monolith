package com.plantarena.geo.domain;

/**
 * Geohash (раздел 8): кодирование координат в ячейку фиксированной сетки.
 * Канонический алгоритм base32 (чередование битов долготы/широты, начиная
 * с долготы); точность 1–12 символов. Воспроизводимая учебная аппроксимация:
 * близкие точки по разные стороны границы ячейки попадают в разные группы.
 */
public final class Geohash {

    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";

    private Geohash() {
    }

    public static String encode(double latitude, double longitude, int precision) {
        if (precision < 1 || precision > 12) {
            throw new IllegalArgumentException("Точность geohash 1–12: " + precision);
        }
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("Широта вне диапазона [-90, 90]: " + latitude);
        }
        if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Долгота вне диапазона [-180, 180]: " + longitude);
        }
        double latMin = -90;
        double latMax = 90;
        double lonMin = -180;
        double lonMax = 180;
        StringBuilder hash = new StringBuilder(precision);
        boolean even = true;
        int bit = 0;
        int ch = 0;
        while (hash.length() < precision) {
            if (even) {
                double mid = (lonMin + lonMax) / 2;
                if (longitude >= mid) {
                    ch |= 1 << (4 - bit);
                    lonMin = mid;
                } else {
                    lonMax = mid;
                }
            } else {
                double mid = (latMin + latMax) / 2;
                if (latitude >= mid) {
                    ch |= 1 << (4 - bit);
                    latMin = mid;
                } else {
                    latMax = mid;
                }
            }
            even = !even;
            if (bit < 4) {
                bit++;
            } else {
                hash.append(BASE32.charAt(ch));
                bit = 0;
                ch = 0;
            }
        }
        return hash.toString();
    }
}
