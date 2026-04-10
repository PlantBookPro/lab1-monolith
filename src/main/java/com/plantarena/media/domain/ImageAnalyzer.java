package com.plantarena.media.domain;

/**
 * Порт анализа фактического содержимого изображения (раздел 6): определяет
 * формат по содержимому (не по расширению/MIME клиента) и размеры по заголовку
 * до декодирования растра. Реализация — media.adapter.out.image.
 */
public interface ImageAnalyzer {

    /**
     * @throws UnsupportedImageFormatException содержимое — не JPEG и не PNG
     * @throws ImageResolutionTooHighException больше 20 миллионов пикселей
     */
    AnalyzedImage analyze(byte[] content);
}
