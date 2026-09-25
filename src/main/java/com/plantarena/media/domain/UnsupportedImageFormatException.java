package com.plantarena.media.domain;

/**
 * Фактическое содержимое файла — не JPEG и не PNG (HTTP 415, раздел 13).
 * Доменное исключение единого языка, не знает об HTTP.
 */
public class UnsupportedImageFormatException extends RuntimeException {

    public UnsupportedImageFormatException(String message) {
        super(message);
    }
}
