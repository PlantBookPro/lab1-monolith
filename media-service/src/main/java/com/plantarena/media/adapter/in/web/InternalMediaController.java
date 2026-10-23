package com.plantarena.media.adapter.in.web;

import com.plantarena.media.api.MediaAssetClaims;
import com.plantarena.media.api.MediaAssets;
import com.plantarena.media.application.MediaAssetNotFoundException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/files")
@RequiredArgsConstructor
public class InternalMediaController {

    private final MediaAssets mediaAssets;
    private final MediaAssetClaims mediaAssetClaims;

    @GetMapping("/{assetId}/metadata")
    public MediaAssetMetadataResponse metadata(@PathVariable UUID assetId) {
        return mediaAssets.findById(assetId)
            .map(a -> new MediaAssetMetadataResponse(a.id(), a.ownerId(), a.fingerprint(), a.fingerprintVersion()))
            .orElseThrow(() -> new MediaAssetNotFoundException("Файл не найден: " + assetId));
    }

    @GetMapping("/{assetId}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID assetId) {
        return mediaAssets.loadContent(assetId)
            .map(c -> ResponseEntity.ok().contentType(MediaType.parseMediaType(c.mimeType()))
                .body(c.content()))
            .orElseThrow(() -> new MediaAssetNotFoundException("Файл не найден: " + assetId));
    }

    @PostMapping("/{assetId}/claims")
    public void claim(@PathVariable UUID assetId, @RequestBody ClaimRequest request) {
        mediaAssetClaims.claim(assetId, request.plantId(), request.publiclyVisible());
    }

    @DeleteMapping("/{assetId}/claims/{plantId}")
    public void release(@PathVariable UUID assetId, @PathVariable UUID plantId) {
        mediaAssetClaims.release(assetId, plantId);
    }

    public record MediaAssetMetadataResponse(UUID id, UUID ownerId, String fingerprint, int fingerprintVersion) {}
    public record ClaimRequest(UUID plantId, boolean publiclyVisible) {}
}
