package com.plantarena.geo.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Снимок кластера (раздел 8, алгоритм 2): состав и версия политики неизменны. */
@DisplayName("ClusterSnapshot: фиксация состава эпохи")
class ClusterSnapshotTest {

    @Test
    @DisplayName("фиксация сохраняет ключ, версию политики и состав с locationVersion")
    void фиксация_состава() {
        UUID entryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ClusterMember member = new ClusterMember(entryId, userId, 55.7558, 37.6173, 7L);
        ClusterSnapshot snapshot = ClusterSnapshot.fix(UUID.randomUUID(), "u4pu",
            "geohash-v1-p4", List.of(member), Instant.parse("2026-09-27T10:00:00Z"));
        assertThat(snapshot.clusterKey()).isEqualTo("u4pu");
        assertThat(snapshot.policyVersion()).isEqualTo("geohash-v1-p4");
        assertThat(snapshot.members()).containsExactly(
            new ClusterSnapshot.SnapshotMember(userId, entryId, 7L));
    }

    @Test
    @DisplayName("пустой кластер не фиксируется")
    void пустой_кластер_запрещён() {
        assertThatThrownBy(() -> ClusterSnapshot.fix(UUID.randomUUID(), "u4pu",
            "geohash-v1-p4", List.of(), Instant.parse("2026-09-27T10:00:00Z")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
