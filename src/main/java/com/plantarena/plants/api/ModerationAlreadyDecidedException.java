package com.plantarena.plants.api;

/** По заявке уже зафиксировано другое решение; устаревший результат не применяется (раздел 6). */
public final class ModerationAlreadyDecidedException extends RuntimeException {

    public ModerationAlreadyDecidedException(String message) {
        super(message);
    }
}
