package com.plantarena.media.api;

import java.util.UUID;

/**
 * DTO опубликованного контракта: только то, что нужно downstream (plants) —
 * владелец и отпечаток. storageKey и сырой хэш не раскрываются.
 */
public record MediaAssetData(UUID id, UUID ownerId, String fingerprint, int fingerprintVersion) {
}
