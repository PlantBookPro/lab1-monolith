package com.plantarena.media.application;

/**
 * Файл превышает 10 MiB (HTTP 413, разделы 6 и 13).
 * Доменное исключение единого языка, не знает об HTTP.
 */
public class FileTooLargeException extends RuntimeException {

    public FileTooLargeException(String message) {
        super(message);
    }
}
