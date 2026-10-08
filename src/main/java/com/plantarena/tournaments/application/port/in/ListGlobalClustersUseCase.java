package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.web.PaginationParams;
import java.time.Instant;
import java.util.List;
import java.util.UUID;


public interface ListGlobalClustersUseCase {

    ClusterListResult list(PaginationParams page);

    record ClusterListResult(List<GlobalCluster> items, long total) {
    }

    record GlobalCluster(UUID clusterId, String clusterKey, UUID windowId, int memberCount,
                         Instant closesAt) {
    }
}
