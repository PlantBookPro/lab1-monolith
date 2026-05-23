package com.plantarena.tournaments.adapter.out.geo;

import com.plantarena.geo.api.ClusterAssignment;
import com.plantarena.tournaments.application.port.out.ClusteringGateway;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ACL-адаптер geo → tournaments (раздел 4.3): команда кластеризации через
 * контракт geo.api.ClusterAssignment; geo получает координаты во входной
 * команде. В лабе №2 меняется на HTTP-клиент.
 */
@Component
public class InProcessClusteringGateway implements ClusteringGateway {

    private final ClusterAssignment clusterAssignment;

    public InProcessClusteringGateway(ClusterAssignment clusterAssignment) {
        this.clusterAssignment = clusterAssignment;
    }

    @Override
    public List<AssignedCluster> assignClusters(UUID epochId, List<MemberLocation> members) {
        List<ClusterAssignment.Member> apiMembers = members.stream()
            .map(member -> new ClusterAssignment.Member(member.entryId(), member.userId(),
                member.latitude(), member.longitude(), member.locationVersion()))
            .toList();
        return clusterAssignment.assignClusters(epochId, apiMembers).stream()
            .map(cluster -> new AssignedCluster(cluster.snapshotId(), cluster.clusterKey(),
                cluster.entryIds()))
            .toList();
    }
}
