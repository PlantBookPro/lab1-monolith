package com.plantarena.geo.application;

import com.plantarena.geo.api.ClusterAssignment;
import com.plantarena.geo.application.support.InMemoryClusterSnapshotRepository;
import com.plantarena.geo.domain.GeohashClusteringPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Фасад кластеризации: команда → снимки → ответ с составами. */
@DisplayName("ClusterAssignmentFacade: фиксация кластеров эпохи")
class ClusterAssignmentFacadeTest {

    private final InMemoryClusterSnapshotRepository snapshots = new InMemoryClusterSnapshotRepository();
    private final ClusterAssignment facade = new ClusterAssignmentFacade(
        new GeohashClusteringPolicy(4), snapshots,
        Clock.fixed(Instant.parse("2026-09-27T10:00:00Z"), ZoneOffset.UTC));

    @Test
    @DisplayName("группирует по ячейкам, сохраняет снимки, возвращает snapshotId + ключ + состав")
    void фиксация_кластеров() {
        UUID epochId = UUID.randomUUID();
        UUID entry1 = UUID.randomUUID();
        UUID entry2 = UUID.randomUUID();
        UUID entry3 = UUID.randomUUID();
        List<ClusterAssignment.AssignedCluster> clusters = facade.assignClusters(epochId,
            List.of(
                new ClusterAssignment.Member(entry1, UUID.randomUUID(), 55.7558, 37.6173, 1L),
                new ClusterAssignment.Member(entry2, UUID.randomUUID(), 55.7558, 37.6173, 2L),
                new ClusterAssignment.Member(entry3, UUID.randomUUID(), 59.9375, 30.3086, 3L)));
        assertThat(clusters).hasSize(2);
        ClusterAssignment.AssignedCluster moscow = clusters.stream()
            .filter(cluster -> cluster.entryIds().size() == 2).findFirst().orElseThrow();
        assertThat(moscow.entryIds()).containsExactlyInAnyOrder(entry1, entry2);
        assertThat(moscow.clusterKey()).isEqualTo("ucfv");
        assertThat(snapshots.findByEpochId(epochId)).hasSize(2);
        assertThat(snapshots.findById(moscow.snapshotId())).isPresent();
    }
}
