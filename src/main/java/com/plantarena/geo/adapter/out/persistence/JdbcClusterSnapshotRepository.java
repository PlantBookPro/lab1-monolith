package com.plantarena.geo.adapter.out.persistence;

import com.plantarena.geo.domain.ClusterSnapshot;
import com.plantarena.geo.domain.ClusterSnapshotRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;


@Repository
@RequiredArgsConstructor
public class JdbcClusterSnapshotRepository implements ClusterSnapshotRepository {

    private static final String SELECT_SNAPSHOTS = """
        SELECT s.id, s.epoch_id, s.cluster_key, s.policy_version, s.created_at,
               m.user_id, m.entry_id, m.location_version
        FROM geo.cluster_snapshot s
        LEFT JOIN geo.cluster_member m ON m.snapshot_id = s.id
        """;

    private final JdbcClient jdbcClient;

    @Override
    @Transactional
    public ClusterSnapshot save(ClusterSnapshot snapshot) {
        int updated = jdbcClient.sql("""
                UPDATE geo.cluster_snapshot
                SET epoch_id = :epochId,
                    cluster_key = :clusterKey,
                    policy_version = :policyVersion,
                    created_at = :createdAt
                WHERE id = :id
                """)
            .param("id", snapshot.id())
            .param("epochId", snapshot.epochId())
            .param("clusterKey", snapshot.clusterKey())
            .param("policyVersion", snapshot.policyVersion())
            .param("createdAt", Timestamp.from(snapshot.createdAt()))
            .update();
        if (updated == 0) {
            jdbcClient.sql("""
                    INSERT INTO geo.cluster_snapshot (id, epoch_id, cluster_key,
                        policy_version, created_at)
                    VALUES (:id, :epochId, :clusterKey, :policyVersion, :createdAt)
                    """)
                .param("id", snapshot.id())
                .param("epochId", snapshot.epochId())
                .param("clusterKey", snapshot.clusterKey())
                .param("policyVersion", snapshot.policyVersion())
                .param("createdAt", Timestamp.from(snapshot.createdAt()))
                .update();
        }
        jdbcClient.sql("DELETE FROM geo.cluster_member WHERE snapshot_id = :snapshotId")
            .param("snapshotId", snapshot.id())
            .update();
        for (ClusterSnapshot.SnapshotMember member : snapshot.members()) {
            jdbcClient.sql("""
                    INSERT INTO geo.cluster_member (snapshot_id, user_id, entry_id, location_version)
                    VALUES (:snapshotId, :userId, :entryId, :locationVersion)
                    """)
                .param("snapshotId", snapshot.id())
                .param("userId", member.userId())
                .param("entryId", member.entryId())
                .param("locationVersion", member.locationVersion())
                .update();
        }
        return snapshot;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ClusterSnapshot> findByEpochId(UUID epochId) {
        return selectSnapshots("WHERE s.epoch_id = :epochId ORDER BY s.cluster_key ASC, m.entry_id ASC")
            .param("epochId", epochId)
            .query(asSnapshotsList());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ClusterSnapshot> findById(UUID id) {
        return selectSnapshots("WHERE s.id = :id ORDER BY m.entry_id ASC")
            .param("id", id)
            .query(asSnapshotsList())
            .stream().findFirst();
    }

    private JdbcClient.StatementSpec selectSnapshots(String tail) {
        return jdbcClient.sql(SELECT_SNAPSHOTS + tail);
    }

    private ResultSetExtractor<List<ClusterSnapshot>> asSnapshotsList() {
        return this::readSnapshots;
    }

    private List<ClusterSnapshot> readSnapshots(ResultSet rs) throws SQLException {
        Map<UUID, SnapshotRow> byId = new LinkedHashMap<>();
        while (rs.next()) {
            UUID id = rs.getObject("id", UUID.class);
            SnapshotRow row = byId.get(id);
            if (row == null) {
                row = new SnapshotRow(rs);
                byId.put(id, row);
            }
            UUID memberUserId = rs.getObject("user_id", UUID.class);
            if (memberUserId != null) {
                row.members.add(new ClusterSnapshot.SnapshotMember(memberUserId,
                    rs.getObject("entry_id", UUID.class), rs.getLong("location_version")));
            }
        }
        return byId.values().stream().map(SnapshotRow::toDomain).toList();
    }

    private static final class SnapshotRow {

        private final UUID id;
        private final UUID epochId;
        private final String clusterKey;
        private final String policyVersion;
        private final Instant createdAt;
        private final List<ClusterSnapshot.SnapshotMember> members = new ArrayList<>();

        private SnapshotRow(ResultSet rs) throws SQLException {
            id = rs.getObject("id", UUID.class);
            epochId = rs.getObject("epoch_id", UUID.class);
            clusterKey = rs.getString("cluster_key");
            policyVersion = rs.getString("policy_version");
            createdAt = rs.getTimestamp("created_at").toInstant();
        }

        private ClusterSnapshot toDomain() {
            return ClusterSnapshot.restore(id, epochId, clusterKey, policyVersion,
                members, createdAt);
        }
    }
}
