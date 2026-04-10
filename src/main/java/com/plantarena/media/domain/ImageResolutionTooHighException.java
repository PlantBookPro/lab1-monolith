package com.plantarena.media.domain;

/**
 * Изображение превышает 20 миллионов пикселей (HTTP 413, разделы 6 и 13).
 * Доменное исключение единого языка, не знает об HTTP.
 */
public class ImageResolutionTooHighException extends RuntimeException {

    public ImageResolutionTooHighException(String message) {
        super(message);
    }
}
