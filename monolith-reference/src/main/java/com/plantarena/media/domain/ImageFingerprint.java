package com.plantarena.media.domain;

import java.util.regex.Pattern;


public record ImageFingerprint(String value, int version) {

    private static final Pattern HEX_64 = Pattern.compile("[0-9a-f]{64}");

    public ImageFingerprint {
        if (value == null || !HEX_64.matcher(value).matches()) {
            throw new IllegalArgumentException("Отпечаток должен быть 64 hex-символами: " + value);
        }
        if (version < 1) {
            throw new IllegalArgumentException("Версия алгоритма отпечатка должна быть положительной");
        }
    }
}
