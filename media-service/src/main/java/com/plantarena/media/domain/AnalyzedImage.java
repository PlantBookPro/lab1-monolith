package com.plantarena.media.domain;


public record AnalyzedImage(ImageFormat format, int width, int height, int[] argb) {

    public AnalyzedImage {
        if (format == null) {
            throw new IllegalArgumentException("Формат изображения обязателен");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Размеры изображения должны быть положительными");
        }
        if (argb == null || argb.length != width * height) {
            throw new IllegalArgumentException("Массив пикселей должен совпадать с размерами изображения");
        }
    }
}
