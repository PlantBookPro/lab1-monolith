package com.plantarena.tournaments.application.port.out;

import java.util.List;
import java.util.UUID;


public interface ClusteringGateway {

    
    List<AssignedCluster> assignClusters(UUID epochId, List<MemberLocation> members);

    
    record MemberLocation(UUID entryId, UUID userId, double latitude, double longitude,
                          long locationVersion) {
    }

    
    record AssignedCluster(UUID clusterId, String clusterKey, List<UUID> entryIds) {
    }
}
