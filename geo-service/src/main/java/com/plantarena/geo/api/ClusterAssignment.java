package com.plantarena.geo.api;

import java.util.List;
import java.util.UUID;


public interface ClusterAssignment {

    List<AssignedCluster> assignClusters(UUID epochId, List<Member> members);

    
    record Member(UUID entryId, UUID userId, double latitude, double longitude,
                  long locationVersion) {
    }

    
    record AssignedCluster(UUID snapshotId, String clusterKey, List<UUID> entryIds) {
    }
}
