package com.plantarena.plants.adapter.out.media;

import com.plantarena.plants.api.client.MediaDirectoryClient;
import com.plantarena.plants.application.AssetAlreadyClaimedException;
import com.plantarena.plants.application.port.out.MediaAssetClaimsGateway;
import com.plantarena.plants.application.port.out.MediaAssetsGateway;
import com.plantarena.plants.application.port.out.MediaAssetsGateway.AssetMetadata;
import feign.FeignException;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class HttpMediaGateway implements MediaAssetsGateway, MediaAssetClaimsGateway {

    private final MediaDirectoryClient mediaClient;

    @Override
    public Optional<AssetMetadata> findById(UUID assetId) {
        try {
            MediaDirectoryClient.MediaAssetMetadataView view = mediaClient.getMetadata(assetId);
            return Optional.of(new AssetMetadata(view.id(), view.ownerId(),
                view.fingerprint(), view.fingerprintVersion()));
        } catch (FeignException.NotFound e) {
            return Optional.empty();
        }
    }

    @Override
    public void claim(UUID assetId, UUID plantId, boolean publiclyVisible) {
        try {
            mediaClient.claim(assetId, new MediaDirectoryClient.ClaimRequest(plantId, publiclyVisible));
        } catch (FeignException.Conflict e) {
            throw new AssetAlreadyClaimedException("Файл уже задействован другим растением: " + assetId);
        }
    }

    @Override
    public void release(UUID assetId, UUID plantId) {
        mediaClient.release(assetId, plantId);
    }
}
