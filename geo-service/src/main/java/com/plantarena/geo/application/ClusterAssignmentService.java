package com.plantarena.geo.application;

import com.plantarena.geo.api.ClusterAssignment;
import com.plantarena.geo.domain.ClusterMember;
import com.plantarena.geo.domain.ClusterSnapshot;
import com.plantarena.geo.domain.ClusterSnapshotRepository;
import com.plantarena.geo.domain.ClusteringPolicy;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class ClusterAssignmentService {

    private final ClusterSnapshotRepository repository;
    private final ClusteringPolicy clusteringPolicy;
    private final Clock clock;

    public Mono<List<ClusterAssignment.AssignedCluster>> assignClusters(UUID epochId,
                                                                        List<ClusterAssignment.Member> members) {
        List<ClusterMember> domainMembers = members.stream()
            .map(m -> new ClusterMember(m.entryId(), m.userId(), m.latitude(), m.longitude(),
                m.locationVersion()))
            .toList();
        return Mono.fromCallable(() -> clusteringPolicy.cluster(domainMembers))
            .map(clusters -> toSnapshots(epochId, clusters))
            .flatMap(snapshots -> Flux.fromIterable(snapshots)
                .flatMap(repository::save)
                .then(Mono.fromSupplier(() -> snapshots.stream()
                    .map(snapshot -> new ClusterAssignment.AssignedCluster(
                        snapshot.id(), snapshot.clusterKey(),
                        snapshot.members().stream()
                            .map(ClusterSnapshot.SnapshotMember::entryId)
                            .toList()))
                    .toList())));
    }

    public Flux<ClusterSnapshot> findByEpoch(UUID epochId) {
        return repository.findByEpochId(epochId);
    }

    private List<ClusterSnapshot> toSnapshots(UUID epochId, Map<String, List<ClusterMember>> clusters) {
        return clusters.entrySet().stream()
            .map(entry -> ClusterSnapshot.fix(epochId, entry.getKey(),
                clusteringPolicy.policyVersion(), entry.getValue(), clock.instant()))
            .toList();
    }
}
