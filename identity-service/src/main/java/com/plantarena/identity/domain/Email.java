package com.plantarena.identity.domain;

import java.util.Locale;


public record Email(String value) {

    private static final String PATTERN = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

    public Email {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches(PATTERN)) {
            throw new IllegalArgumentException("Некорректный email: " + value);
        }
        value = normalized;
    }
}
