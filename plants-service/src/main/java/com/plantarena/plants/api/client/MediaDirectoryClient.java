package com.plantarena.plants.api.client;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "media-service")
public interface MediaDirectoryClient {

    @GetMapping("/internal/files/{assetId}/metadata")
    MediaAssetMetadataView getMetadata(@PathVariable("assetId") UUID assetId);

    @GetMapping("/internal/files/{assetId}/content")
    ResponseEntity<byte[]> getContent(@PathVariable("assetId") UUID assetId);

    @PostMapping("/internal/files/{assetId}/claims")
    void claim(@PathVariable("assetId") UUID assetId, @RequestBody ClaimRequest request);

    @DeleteMapping("/internal/files/{assetId}/claims/{plantId}")
    void release(@PathVariable("assetId") UUID assetId, @PathVariable("plantId") UUID plantId);

    record MediaAssetMetadataView(UUID id, UUID ownerId, String fingerprint, int fingerprintVersion) {}
    record ClaimRequest(UUID plantId, boolean publiclyVisible) {}
}
