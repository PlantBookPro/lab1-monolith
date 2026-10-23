package com.plantarena.geo.adapter.out.persistence;

import com.plantarena.geo.domain.ClusterSnapshot;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
@RequiredArgsConstructor
public class R2dbcClusterSnapshotRepository implements com.plantarena.geo.domain.ClusterSnapshotRepository {

    private final ClusterSnapshotRowRepository snapshotRows;
    private final DatabaseClient databaseClient;

    @Override
    @Transactional
    public Mono<ClusterSnapshot> save(ClusterSnapshot snapshot) {
        return snapshotRows.insert(snapshot.id(), snapshot.epochId(), snapshot.clusterKey(),
                snapshot.policyVersion(), snapshot.createdAt())
            .then(deleteMembers(snapshot.id()))
            .then(insertMembers(snapshot))
            .thenReturn(snapshot);
    }

    @Override
    public Flux<ClusterSnapshot> findByEpochId(UUID epochId) {
        return snapshotRows.findByEpochId(epochId)
            .flatMapSequential(this::loadWithMembers);
    }

    @Override
    public Mono<ClusterSnapshot> findById(UUID id) {
        return snapshotRows.findRowById(id).flatMap(this::loadWithMembers);
    }

    private Mono<Void> deleteMembers(UUID snapshotId) {
        return databaseClient.sql("DELETE FROM geo.cluster_member WHERE snapshot_id = :snapshotId")
            .bind("snapshotId", snapshotId)
            .then();
    }

    private Mono<Void> insertMembers(ClusterSnapshot snapshot) {
        return Flux.fromIterable(snapshot.members())
            .flatMap(m -> databaseClient.sql("""
                    INSERT INTO geo.cluster_member (snapshot_id, user_id, entry_id, location_version)
                    VALUES (:snapshotId, :userId, :entryId, :locationVersion)
                    """)
                .bind("snapshotId", snapshot.id())
                .bind("userId", m.userId())
                .bind("entryId", m.entryId())
                .bind("locationVersion", m.locationVersion())
                .then())
            .then();
    }

    private Mono<ClusterSnapshot> loadWithMembers(ClusterSnapshotRow row) {
        return databaseClient.sql("""
                SELECT user_id, entry_id, location_version
                FROM geo.cluster_member WHERE snapshot_id = :snapshotId
                ORDER BY entry_id ASC
                """)
            .bind("snapshotId", row.id())
            .map((r, md) -> new ClusterSnapshot.SnapshotMember(
                r.get("user_id", UUID.class), r.get("entry_id", UUID.class),
                r.get("location_version", Long.class)))
            .all()
            .collectList()
            .map(members -> ClusterSnapshot.restore(row.id(), row.epochId(), row.clusterKey(),
                row.policyVersion(), members, row.createdAt()));
    }
}
