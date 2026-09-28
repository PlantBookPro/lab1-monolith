package com.plantarena.geo.application.support;

import com.plantarena.geo.ClusterSnapshotRepositoryContractTest;
import com.plantarena.geo.domain.ClusterSnapshotRepository;

/** Фейк честен относительно контракта (раздел 14.2). */
class InMemoryClusterSnapshotRepositoryContractTest
        extends ClusterSnapshotRepositoryContractTest {

    private final InMemoryClusterSnapshotRepository repository =
        new InMemoryClusterSnapshotRepository();

    @Override
    protected ClusterSnapshotRepository repository() {
        return repository;
    }
}
