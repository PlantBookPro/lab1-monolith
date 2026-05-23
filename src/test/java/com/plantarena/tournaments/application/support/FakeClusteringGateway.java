package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.application.port.out.ClusteringGateway;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Фейк порта кластеризации: группирует по точным координатам (одна точка — один кластер). */
public class FakeClusteringGateway implements ClusteringGateway {

    public boolean called = false;

    @Override
    public List<AssignedCluster> assignClusters(UUID epochId, List<MemberLocation> members) {
        called = true;
        List<AssignedCluster> result = new ArrayList<>();
        Map<String, List<UUID>> byPoint = new TreeMap<>();
        for (MemberLocation member : members) {
            byPoint.computeIfAbsent(member.latitude() + ":" + member.longitude(),
                key -> new ArrayList<>()).add(member.entryId());
        }
        for (Map.Entry<String, List<UUID>> entry : byPoint.entrySet()) {
            result.add(new AssignedCluster(UUID.randomUUID(),
                "fake-" + entry.getKey().replace(':', '-'), List.copyOf(entry.getValue())));
        }
        return result;
    }
}
