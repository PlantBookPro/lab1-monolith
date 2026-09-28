package com.plantarena.geo.adapter.out.persistence;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Составной ключ cluster_member (snapshot_id, entry_id). */
public class ClusterMemberJpaId implements Serializable {

    private UUID snapshotId;
    private UUID entryId;

    public ClusterMemberJpaId() {
    }

    public ClusterMemberJpaId(UUID snapshotId, UUID entryId) {
        this.snapshotId = snapshotId;
        this.entryId = entryId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClusterMemberJpaId other)) {
            return false;
        }
        return Objects.equals(snapshotId, other.snapshotId)
            && Objects.equals(entryId, other.entryId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(snapshotId, entryId);
    }
}
