package com.plantarena.geo.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Spring Data R2DBC-строки снимков. SQL задан явно через @Query: генератор
 * экранирует @Table-имя как один идентификатор («geo.cluster_snapshot»),
 * из-за чего схема-квалифицированное имя в сгенерированном SQL ломается.
 */
interface ClusterSnapshotRowRepository extends ReactiveCrudRepository<ClusterSnapshotRow, UUID> {

    @Modifying
    @Query("""
        INSERT INTO geo.cluster_snapshot (id, epoch_id, cluster_key, policy_version, created_at)
        VALUES (:id, :epochId, :clusterKey, :policyVersion, :createdAt)
        """)
    Mono<Void> insert(UUID id, UUID epochId, String clusterKey, String policyVersion,
                      Instant createdAt);

    @Query("""
        SELECT id, epoch_id, cluster_key, policy_version, created_at
        FROM geo.cluster_snapshot WHERE epoch_id = :epochId ORDER BY cluster_key ASC
        """)
    Flux<ClusterSnapshotRow> findByEpochId(UUID epochId);

    @Query("""
        SELECT id, epoch_id, cluster_key, policy_version, created_at
        FROM geo.cluster_snapshot WHERE id = :id
        """)
    Mono<ClusterSnapshotRow> findRowById(UUID id);
}
