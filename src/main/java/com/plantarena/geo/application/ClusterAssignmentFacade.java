package com.plantarena.geo.application;

import com.plantarena.geo.api.ClusterAssignment;
import com.plantarena.geo.domain.ClusterMember;
import com.plantarena.geo.domain.ClusterSnapshot;
import com.plantarena.geo.domain.ClusterSnapshotRepository;
import com.plantarena.geo.domain.ClusteringPolicy;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Реализация опубликованного контракта geo.api.ClusterAssignment:
 * группировка политикой → фиксация снимков (состав и версия неизменны) →
 * ответ с составами участий. Время — из внедрённого Clock.
 */
@Component
public class ClusterAssignmentFacade implements ClusterAssignment {

    private final ClusteringPolicy policy;
    private final ClusterSnapshotRepository snapshots;
    private final Clock clock;

    public ClusterAssignmentFacade(ClusteringPolicy policy,
                                   ClusterSnapshotRepository snapshots, Clock clock) {
        this.policy = policy;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    @Override
    public List<AssignedCluster> assignClusters(UUID epochId, List<Member> members) {
        List<ClusterMember> domainMembers = members.stream()
            .map(member -> new ClusterMember(member.entryId(), member.userId(),
                member.latitude(), member.longitude(), member.locationVersion()))
            .toList();
        Map<String, List<ClusterMember>> grouped = policy.cluster(domainMembers);
        List<AssignedCluster> result = new ArrayList<>(grouped.size());
        for (Map.Entry<String, List<ClusterMember>> entry : grouped.entrySet()) {
            ClusterSnapshot snapshot = ClusterSnapshot.fix(epochId, entry.getKey(),
                policy.policyVersion(), entry.getValue(), clock.instant());
            snapshots.save(snapshot);
            result.add(new AssignedCluster(snapshot.id(), snapshot.clusterKey(),
                entry.getValue().stream().map(ClusterMember::entryId).toList()));
        }
        return result;
    }
}
