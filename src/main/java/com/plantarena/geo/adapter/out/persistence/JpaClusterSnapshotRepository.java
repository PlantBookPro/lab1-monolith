package com.plantarena.geo.adapter.out.persistence;

import com.plantarena.geo.domain.ClusterSnapshot;
import com.plantarena.geo.domain.ClusterSnapshotRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;


@Repository
public class JpaClusterSnapshotRepository implements ClusterSnapshotRepository {

    private final ClusterSnapshotJpaRepository jpaRepository;

    public JpaClusterSnapshotRepository(ClusterSnapshotJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public ClusterSnapshot save(ClusterSnapshot snapshot) {
        ClusterSnapshotJpaEntity entity = jpaRepository.findById(snapshot.id())
            .orElseGet(() -> newEntity(snapshot));
        entity.setEpochId(snapshot.epochId());
        entity.setClusterKey(snapshot.clusterKey());
        entity.setPolicyVersion(snapshot.policyVersion());
        entity.setCreatedAt(snapshot.createdAt());
        entity.getMembers().clear();
        for (ClusterSnapshot.SnapshotMember member : snapshot.members()) {
            ClusterMemberJpaEntity memberEntity = new ClusterMemberJpaEntity();
            memberEntity.setSnapshotId(entity.getId());
            memberEntity.setEntryId(member.entryId());
            memberEntity.setUserId(member.userId());
            memberEntity.setLocationVersion(member.locationVersion());
            memberEntity.setSnapshot(entity);
            entity.getMembers().add(memberEntity);
        }
        jpaRepository.saveAndFlush(entity);
        return snapshot;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ClusterSnapshot> findByEpochId(UUID epochId) {
        return jpaRepository.findByEpochIdOrderByClusterKeyAsc(epochId)
            .stream().map(JpaClusterSnapshotRepository::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ClusterSnapshot> findById(UUID id) {
        return jpaRepository.findById(id).map(JpaClusterSnapshotRepository::toDomain);
    }

    private ClusterSnapshotJpaEntity newEntity(ClusterSnapshot snapshot) {
        ClusterSnapshotJpaEntity entity = new ClusterSnapshotJpaEntity();
        entity.setId(snapshot.id());
        return entity;
    }

    private static ClusterSnapshot toDomain(ClusterSnapshotJpaEntity entity) {
        List<ClusterSnapshot.SnapshotMember> members = entity.getMembers().stream()
            .map(member -> new ClusterSnapshot.SnapshotMember(member.getUserId(),
                member.getEntryId(), member.getLocationVersion()))
            .toList();
        return ClusterSnapshot.restore(entity.getId(), entity.getEpochId(),
            entity.getClusterKey(), entity.getPolicyVersion(), members, entity.getCreatedAt());
    }
}
