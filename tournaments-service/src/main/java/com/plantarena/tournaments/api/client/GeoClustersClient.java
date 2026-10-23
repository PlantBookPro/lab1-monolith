package com.plantarena.tournaments.api.client;

import com.plantarena.tournaments.api.client.fallback.GeoClustersClientFallbackFactory;
import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "geo-service", fallbackFactory = GeoClustersClientFallbackFactory.class)
public interface GeoClustersClient {

    @PostMapping("/internal/clusters/assignments")
    List<AssignedClusterView> assign(@RequestBody AssignmentRequest request);

    record AssignmentRequest(UUID epochId, List<MemberView> members) {}

    record MemberView(UUID entryId, UUID userId, double latitude, double longitude, long locationVersion) {}

    record AssignedClusterView(UUID snapshotId, String clusterKey, List<UUID> entryIds) {}
}
