package com.plantarena.tournaments.adapter.out.geo;

import com.plantarena.tournaments.api.client.GeoClustersClient;
import com.plantarena.tournaments.application.port.out.ClusteringGateway;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HttpClusteringGateway implements ClusteringGateway {

    private final GeoClustersClient geoClient;

    @Override
    public List<AssignedCluster> assignClusters(UUID epochId, List<MemberLocation> members) {
        List<GeoClustersClient.MemberView> apiMembers = members.stream()
            .map(member -> new GeoClustersClient.MemberView(member.entryId(), member.userId(),
                member.latitude(), member.longitude(), member.locationVersion()))
            .toList();
        return geoClient.assign(new GeoClustersClient.AssignmentRequest(epochId, apiMembers)).stream()
            .map(cluster -> new AssignedCluster(cluster.snapshotId(), cluster.clusterKey(),
                cluster.entryIds()))
            .toList();
    }
}
