package com.plantarena.geo.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("geo.cluster_snapshot")
public record ClusterSnapshotRow(
    @Id UUID id,
    UUID epochId,
    String clusterKey,
    String policyVersion,
    Instant createdAt) {}
