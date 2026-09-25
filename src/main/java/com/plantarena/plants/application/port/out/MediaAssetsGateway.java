package com.plantarena.plants.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Выходной порт plants: метаданные asset из media (Customer–Supplier,
 * раздел 4.3). Потребитель владеет портом со своими типами (ACL);
 * реализация — plants.adapter.out.media (in-process, лаба №2 — Feign).
 */
public interface MediaAssetsGateway {

    Optional<AssetMetadata> findById(UUID assetId);

    /** ACL-DTO: только нужное plants; storageKey не пересекает границу. */
    record AssetMetadata(UUID id, UUID ownerId, String fingerprint, int fingerprintVersion) {
    }
}
