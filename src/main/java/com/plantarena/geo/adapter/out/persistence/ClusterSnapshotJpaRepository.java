package com.plantarena.geo.adapter.out.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;


public interface ClusterSnapshotJpaRepository extends JpaRepository<ClusterSnapshotJpaEntity,
        UUID> {

    List<ClusterSnapshotJpaEntity> findByEpochIdOrderByClusterKeyAsc(UUID epochId);
}
