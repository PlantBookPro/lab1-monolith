package com.plantarena.media.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Опубликованный контракт media для downstream-контекстов (раздел 4.3,
 * Customer–Supplier): метаданные файла без внутренних деталей (storageKey
 * остаётся внутри media). Использует plants (InProcessMediaGateway, Task 6).
 */
public interface MediaAssets {

    Optional<MediaAssetData> findById(UUID assetId);
}
