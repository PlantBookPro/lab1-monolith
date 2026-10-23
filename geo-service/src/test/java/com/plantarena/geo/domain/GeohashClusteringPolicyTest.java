package com.plantarena.geo.domain;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Политика кластеризации (раздел 8): фиксированная geohash-сетка. */
@DisplayName("Geohash-кластеризация: группировка по ячейке, версия политики")
class GeohashClusteringPolicyTest {

    @Test
    @DisplayName("близкие точки в одной ячейке попадают в один кластер, далёкие — в разные")
    void группировка_по_ячейкам() {
        GeohashClusteringPolicy policy = new GeohashClusteringPolicy(4);
        ClusterMember moscow1 = member("e1", 55.7558, 37.6173);
        ClusterMember moscow2 = member("e2", 55.7558, 37.6173);
        ClusterMember spb = member("e3", 59.9375, 30.3086);
        Map<String, List<ClusterMember>> clusters = policy.cluster(List.of(moscow1, moscow2, spb));
        assertThat(clusters).hasSize(2);
        assertThat(clusters.values().stream().flatMap(List::stream))
            .containsExactlyInAnyOrder(moscow1, moscow2, spb);
        assertThat(clusters).hasEntrySatisfying(Geohash.encode(55.7558, 37.6173, 4),
            members -> assertThat(members).containsExactlyInAnyOrder(moscow1, moscow2));
    }

    @Test
    @DisplayName("версия политики фиксирует алгоритм и точность")
    void версия_политики() {
        assertThat(new GeohashClusteringPolicy(4).policyVersion()).isEqualTo("geohash-v1-p4");
    }

    @Test
    @DisplayName("точность вне 1–12 — ошибка конфигурации")
    void неверная_точность() {
        assertThatThrownBy(() -> new GeohashClusteringPolicy(0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private ClusterMember member(String entrySuffix, double lat, double lon) {
        return new ClusterMember(java.util.UUID.nameUUIDFromBytes(entrySuffix.getBytes()),
            java.util.UUID.randomUUID(), lat, lon, 1L);
    }
}
