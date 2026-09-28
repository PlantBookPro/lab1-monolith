package com.plantarena.geo.adapter.out.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** JPA-модель снимка кластера (раздел 11); маппинг в домен — явный. */
@Entity
@Table(name = "cluster_snapshot", schema = "geo")
public class ClusterSnapshotJpaEntity {

    @Id
    private UUID id;

    @Column(name = "epoch_id", nullable = false)
    private UUID epochId;

    @Column(name = "cluster_key", nullable = false)
    private String clusterKey;

    @Column(name = "policy_version", nullable = false)
    private String policyVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ClusterMemberJpaEntity> members = new ArrayList<>();

    UUID getId() {
        return id;
    }

    UUID getEpochId() {
        return epochId;
    }

    String getClusterKey() {
        return clusterKey;
    }

    String getPolicyVersion() {
        return policyVersion;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    List<ClusterMemberJpaEntity> getMembers() {
        return members;
    }

    void setId(UUID id) {
        this.id = id;
    }

    void setEpochId(UUID epochId) {
        this.epochId = epochId;
    }

    void setClusterKey(String clusterKey) {
        this.clusterKey = clusterKey;
    }

    void setPolicyVersion(String policyVersion) {
        this.policyVersion = policyVersion;
    }

    void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
