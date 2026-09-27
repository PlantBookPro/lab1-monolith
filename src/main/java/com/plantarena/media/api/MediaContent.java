package com.plantarena.media.api;

/**
 * DTO опубликованного контракта: байты файла и MIME-тип. storageKey не
 * раскрывается — чтение из FileStorage остаётся внутри media (раздел 4.3).
 */
public record MediaContent(byte[] content, String mimeType) {
}
