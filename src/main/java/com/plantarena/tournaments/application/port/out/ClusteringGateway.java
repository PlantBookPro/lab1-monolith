package com.plantarena.tournaments.application.port.out;

import java.util.List;
import java.util.UUID;

/**
 * Выходной порт tournaments: команда гео-кластеризации эпохи (раздел 8).
 * Адаптер — ACL над geo.api.ClusterAssignment: geo получает координаты во
 * входной команде, сам identity не читает (раздел 4.3). Возвращает
 * зафиксированные кластеры (snapshotId + ключ ячейки + состав).
 */
public interface ClusteringGateway {

    /**
     * Зафиксировать кластеры эпохи: группировка по ячейке geohash, снимки
     * состава неизменны (раздел 8, алгоритм 2).
     */
    List<AssignedCluster> assignClusters(UUID epochId, List<MemberLocation> members);

    /** Участник кластеризации: entry + владелец + координаты на момент эпохи. */
    record MemberLocation(UUID entryId, UUID userId, double latitude, double longitude,
                          long locationVersion) {
    }

    /** Зафиксированный кластер: snapshotId (идентификатор наружу), ключ, состав. */
    record AssignedCluster(UUID clusterId, String clusterKey, List<UUID> entryIds) {
    }
}
