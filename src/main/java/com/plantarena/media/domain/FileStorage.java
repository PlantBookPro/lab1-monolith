package com.plantarena.media.domain;


public interface FileStorage {

    String save(byte[] content, ImageFormat format);

    byte[] read(String storageKey);

    void delete(String storageKey);
}
