package com.plantarena.plants.adapter.out.media;

import com.plantarena.media.api.AssetInUseException;
import com.plantarena.media.api.MediaAssetClaims;
import com.plantarena.media.api.MediaAssets;
import com.plantarena.plants.application.AssetAlreadyClaimedException;
import com.plantarena.plants.application.port.out.MediaAssetClaimsGateway;
import com.plantarena.plants.application.port.out.MediaAssetsGateway;
import com.plantarena.plants.application.port.out.MediaAssetsGateway.AssetMetadata;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ACL-адаптер media → plants (раздел 4.3, Customer–Supplier): in-process вызов
 * опубликованного контракта media.api. Потребитель владеет портом со своими
 * типами (MediaAssetsGateway/MediaAssetClaimsGateway); в лабе №2 адаптер
 * меняется на HTTP/Feign, домен и application plants не меняются.
 * Конфликт задействованности переводится в термины plants.
 */
@Component
public class InProcessMediaGateway implements MediaAssetsGateway, MediaAssetClaimsGateway {

    private final MediaAssets mediaAssets;
    private final MediaAssetClaims mediaAssetClaims;

    public InProcessMediaGateway(MediaAssets mediaAssets, MediaAssetClaims mediaAssetClaims) {
        this.mediaAssets = mediaAssets;
        this.mediaAssetClaims = mediaAssetClaims;
    }

    @Override
    public Optional<AssetMetadata> findById(UUID assetId) {
        return mediaAssets.findById(assetId)
            .map(asset -> new AssetMetadata(asset.id(), asset.ownerId(),
                asset.fingerprint(), asset.fingerprintVersion()));
    }

    @Override
    public void claim(UUID assetId, UUID plantId, boolean publiclyVisible) {
        try {
            mediaAssetClaims.claim(assetId, plantId, publiclyVisible);
        } catch (AssetInUseException e) {
            throw new AssetAlreadyClaimedException(e.getMessage());
        }
    }

    @Override
    public void release(UUID assetId, UUID plantId) {
        mediaAssetClaims.release(assetId, plantId);
    }
}
