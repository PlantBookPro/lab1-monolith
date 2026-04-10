package com.plantarena.geo.domain;

import java.util.List;
import java.util.Map;

/**
 * Доменная политика кластеризации (раздел 8): географическая сетка с
 * фиксированной точностью за `ClusteringPolicy`; версия фиксируется в каждом
 * снимке. K-means/DBSCAN не требуются (учебная аппроксимация).
 */
public interface ClusteringPolicy {

    /** Версия политики: алгоритм + точность (фиксируется в ClusterSnapshot). */
    String policyVersion();

    /** Группировка участников по ячейкам: ключ ячейки → состав. */
    Map<String, List<ClusterMember>> cluster(List<ClusterMember> members);
}
