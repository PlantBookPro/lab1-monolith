package com.plantarena.moderation.api.client;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * media-service отдаёт контент сырыми байтами с Content-Type-заголовком
 * (не JSON): метод объявлен как ResponseEntity&lt;byte[]&gt;, маппинг в
 * MediaContent выполняет HttpMediaContentGateway.
 */
@FeignClient(name = "media-service")
public interface MediaContentClient {

    @GetMapping("/internal/files/{assetId}/content")
    ResponseEntity<byte[]> getContent(@PathVariable("assetId") UUID assetId);
}
