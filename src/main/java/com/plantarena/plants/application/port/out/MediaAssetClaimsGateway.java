package com.plantarena.plants.application.port.out;

import java.util.UUID;


public interface MediaAssetClaimsGateway {

    
    void claim(UUID assetId, UUID plantId, boolean publiclyVisible);

    
    void release(UUID assetId, UUID plantId);
}
