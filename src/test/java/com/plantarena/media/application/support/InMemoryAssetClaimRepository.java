package com.plantarena.media.application.support;

import com.plantarena.media.application.port.out.AssetClaimRepository;
import com.plantarena.media.domain.AssetClaim;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory репозиторий задействованности для application-тестов. */
public class InMemoryAssetClaimRepository implements AssetClaimRepository {

    public final Map<UUID, AssetClaim> claims = new ConcurrentHashMap<>();

    @Override
    public Optional<AssetClaim> findByAssetId(UUID assetId) {
        return Optional.ofNullable(claims.get(assetId));
    }

    @Override
    public AssetClaim save(AssetClaim claim) {
        claims.put(claim.assetId(), claim);
        return claim;
    }

    @Override
    public void deleteByAssetId(UUID assetId) {
        claims.remove(assetId);
    }
}
