package com.plantarena.geo.application.support;

import com.plantarena.geo.domain.ClusterSnapshot;
import com.plantarena.geo.domain.ClusterSnapshotRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory фейк ClusterSnapshotRepository. */
public class InMemoryClusterSnapshotRepository implements ClusterSnapshotRepository {

    public final Map<UUID, ClusterSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public ClusterSnapshot save(ClusterSnapshot snapshot) {
        snapshots.put(snapshot.id(), snapshot);
        return snapshot;
    }

    @Override
    public List<ClusterSnapshot> findByEpochId(UUID epochId) {
        return snapshots.values().stream()
            .filter(snapshot -> snapshot.epochId().equals(epochId)).toList();
    }

    @Override
    public Optional<ClusterSnapshot> findById(UUID id) {
        return Optional.ofNullable(snapshots.get(id));
    }
}
