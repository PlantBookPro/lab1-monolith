package com.plantarena.media.application;

import com.plantarena.media.api.AssetInUseException;
import com.plantarena.media.api.MediaAssetClaims;
import com.plantarena.media.application.port.out.AssetClaimRepository;
import com.plantarena.media.domain.AssetClaim;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация команд задействованности (ADR-008). Один файл — одно
 * неархивированное растение: PK asset_id. Повтор claim того же растения —
 * upsert публичности (модерация APPROVED), чужого — конфликт; release
 * чужого растения и повтор release — no-op (идемпотентность доставки).
 */
@Service
public class MediaAssetClaimsFacade implements MediaAssetClaims {

    private final AssetClaimRepository claims;
    private final Clock clock;

    public MediaAssetClaimsFacade(AssetClaimRepository claims, Clock clock) {
        this.claims = claims;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void claim(UUID assetId, UUID plantId, boolean publiclyVisible) {
        claims.findByAssetId(assetId).ifPresent(existing -> {
            if (!existing.plantId().equals(plantId)) {
                throw new AssetInUseException(
                    "Файл уже задействован растением: " + existing.plantId());
            }
        });
        claims.save(AssetClaim.claimed(assetId, plantId, publiclyVisible, clock.instant()));
    }

    @Override
    @Transactional
    public void release(UUID assetId, UUID plantId) {
        claims.findByAssetId(assetId)
            .filter(claim -> claim.plantId().equals(plantId))
            .ifPresent(claim -> claims.deleteByAssetId(assetId));
    }
}
