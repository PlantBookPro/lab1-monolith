package com.plantarena.geo.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Порт репозитория агрегата ClusterSnapshot. */
public interface ClusterSnapshotRepository {

    ClusterSnapshot save(ClusterSnapshot snapshot);

    List<ClusterSnapshot> findByEpochId(UUID epochId);

    Optional<ClusterSnapshot> findById(UUID id);
}
