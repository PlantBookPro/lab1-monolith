package com.plantarena.tournaments.application;

public class DependencyUnavailableException extends RuntimeException {

    public DependencyUnavailableException(String service, Throwable cause) {
        super("Зависимый сервис недоступен: " + service, cause);
    }
}
