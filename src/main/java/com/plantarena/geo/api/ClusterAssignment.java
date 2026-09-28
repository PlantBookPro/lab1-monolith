package com.plantarena.geo.api;

import java.util.List;
import java.util.UUID;

/**
 * Опубликованный контракт geo (Upstream для tournaments, раздел 4.3):
 * команда фиксации кластеров эпохи. Координаты приходят во входной команде —
 * geo сам identity не читает. Возвращает зафиксированные снимки: snapshotId
 * (идентификатор кластера наружу), ключ ячейки и состав участий.
 */
public interface ClusterAssignment {

    List<AssignedCluster> assignClusters(UUID epochId, List<Member> members);

    /** Участник: entry + владелец + координаты на момент эпохи. */
    record Member(UUID entryId, UUID userId, double latitude, double longitude,
                  long locationVersion) {
    }

    /** Зафиксированный кластер эпохи. */
    record AssignedCluster(UUID snapshotId, String clusterKey, List<UUID> entryIds) {
    }
}
