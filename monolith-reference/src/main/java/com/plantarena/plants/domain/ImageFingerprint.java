package com.plantarena.plants.domain;


public record ImageFingerprint(String value, int algorithmVersion) {

    private static final String HEX_64 = "[0-9a-f]{64}";

    public ImageFingerprint {
        if (value == null || !value.matches(HEX_64)) {
            throw new IllegalArgumentException(
                "Отпечаток должен быть 64 hex-символа: " + value);
        }
        if (algorithmVersion < 1) {
            throw new IllegalArgumentException("Версия алгоритма должна быть ≥ 1");
        }
    }
}
