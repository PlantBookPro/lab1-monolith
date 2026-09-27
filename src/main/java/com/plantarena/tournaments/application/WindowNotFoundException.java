package com.plantarena.tournaments.application;

/** Окно не найдено или скрыто политикой приватности (404). */
public final class WindowNotFoundException extends RuntimeException {
    public WindowNotFoundException(String message) {
        super(message);
    }
}
