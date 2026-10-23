package com.plantarena.plants.application.support;

import com.plantarena.plants.application.port.out.MediaAssetClaimsGateway;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Фейк порта MediaAssetClaimsGateway: журнал команд + состояние занятости. */
public class FakeMediaAssetClaims implements MediaAssetClaimsGateway {

    public final List<String> calls = new ArrayList<>();
    public final List<UUID> claimedAssets = new ArrayList<>();
    public final List<UUID> publicAssets = new ArrayList<>();

    @Override
    public void claim(UUID assetId, UUID plantId, boolean publiclyVisible) {
        calls.add("claim " + assetId + " " + plantId + " public=" + publiclyVisible);
        if (!claimedAssets.contains(assetId)) {
            claimedAssets.add(assetId);
        }
        if (publiclyVisible && !publicAssets.contains(assetId)) {
            publicAssets.add(assetId);
        }
        if (!publiclyVisible) {
            publicAssets.remove(assetId);
        }
    }

    @Override
    public void release(UUID assetId, UUID plantId) {
        calls.add("release " + assetId + " " + plantId);
        claimedAssets.remove(assetId);
        publicAssets.remove(assetId);
    }
}
