package com.plantarena.geo.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;


@Entity
@Table(name = "cluster_member", schema = "geo")
@IdClass(ClusterMemberJpaId.class)
public class ClusterMemberJpaEntity {

    @Id
    @Column(name = "snapshot_id")
    private UUID snapshotId;

    @Id
    @Column(name = "entry_id")
    private UUID entryId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "location_version", nullable = false)
    private long locationVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", insertable = false, updatable = false)
    private ClusterSnapshotJpaEntity snapshot;

    UUID getEntryId() {
        return entryId;
    }

    UUID getUserId() {
        return userId;
    }

    long getLocationVersion() {
        return locationVersion;
    }

    void setEntryId(UUID entryId) {
        this.entryId = entryId;
    }

    void setSnapshotId(UUID snapshotId) {
        this.snapshotId = snapshotId;
    }

    void setUserId(UUID userId) {
        this.userId = userId;
    }

    void setLocationVersion(long locationVersion) {
        this.locationVersion = locationVersion;
    }

    void setSnapshot(ClusterSnapshotJpaEntity snapshot) {
        this.snapshot = snapshot;
    }
}
