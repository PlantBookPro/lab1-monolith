package com.plantarena.media.api;

import java.util.UUID;


public interface MediaAssetClaims {

    void claim(UUID assetId, UUID plantId, boolean publiclyVisible);

    void release(UUID assetId, UUID plantId);
}
