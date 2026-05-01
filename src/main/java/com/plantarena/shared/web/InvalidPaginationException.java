package com.plantarena.shared.web;

/**
 * Параметры пагинации вне допустимого диапазона (HTTP 400).
 */
public class InvalidPaginationException extends RuntimeException {

    public InvalidPaginationException(String message) {
        super(message);
    }
}
