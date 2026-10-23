package com.plantarena.geo.domain;

import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ClusterSnapshotRepository {

    Mono<ClusterSnapshot> save(ClusterSnapshot snapshot);

    Flux<ClusterSnapshot> findByEpochId(UUID epochId);

    Mono<ClusterSnapshot> findById(UUID id);
}
