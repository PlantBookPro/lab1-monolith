package com.plantarena.media.application.port.out;

import com.plantarena.media.domain.AssetClaim;
import java.util.Optional;
import java.util.UUID;

/** Порт хранилища задействованности (реализация — JPA, Step 6). */
public interface AssetClaimRepository {

    Optional<AssetClaim> findByAssetId(UUID assetId);

    AssetClaim save(AssetClaim claim);

    void deleteByAssetId(UUID assetId);
}
