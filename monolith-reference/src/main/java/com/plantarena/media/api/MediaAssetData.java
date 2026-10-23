package com.plantarena.media.api;

import java.util.UUID;


public record MediaAssetData(UUID id, UUID ownerId, String fingerprint, int fingerprintVersion) {
}
