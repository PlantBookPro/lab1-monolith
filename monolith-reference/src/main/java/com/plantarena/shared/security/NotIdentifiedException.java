package com.plantarena.shared.security;


public class NotIdentifiedException extends RuntimeException {

    public NotIdentifiedException(String message) {
        super(message);
    }
}
