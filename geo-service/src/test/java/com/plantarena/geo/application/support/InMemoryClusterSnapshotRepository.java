package com.plantarena.geo.application.support;

import com.plantarena.geo.domain.ClusterSnapshot;
import com.plantarena.geo.domain.ClusterSnapshotRepository;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Реактивный in-memory фейк ClusterSnapshotRepository (ConcurrentHashMap). */
public class InMemoryClusterSnapshotRepository implements ClusterSnapshotRepository {

    public final Map<UUID, ClusterSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public Mono<ClusterSnapshot> save(ClusterSnapshot snapshot) {
        snapshots.put(snapshot.id(), snapshot);
        return Mono.just(snapshot);
    }

    @Override
    public Flux<ClusterSnapshot> findByEpochId(UUID epochId) {
        return Flux.fromIterable(snapshots.values().stream()
            .filter(snapshot -> snapshot.epochId().equals(epochId))
            .toList());
    }

    @Override
    public Mono<ClusterSnapshot> findById(UUID id) {
        return Mono.justOrEmpty(snapshots.get(id));
    }
}
