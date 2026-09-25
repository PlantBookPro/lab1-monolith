package com.plantarena.media.domain;

/**
 * Порт файлового хранилища (раздел 6): локальное на Docker volume в лабе №1,
 * S3-совместимый file-service в лабе №4 — меняется только адаптер.
 * Ключ генерирует хранилище; перезапись невозможна.
 */
public interface FileStorage {

    String save(byte[] content, ImageFormat format);

    byte[] read(String storageKey);

    void delete(String storageKey);
}
