package com.plantarena.geo.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Агрегат geo (раздел 8, алгоритм 2): зафиксированный кластер эпохи. Состав и
 * версия политики неизменны после фиксации; изменяется только созданием.
 */
public final class ClusterSnapshot {

    private final UUID id;
    private final UUID epochId;
    private final String clusterKey;
    private final String policyVersion;
    private final List<SnapshotMember> members;
    private final Instant createdAt;

    private ClusterSnapshot(UUID id, UUID epochId, String clusterKey, String policyVersion,
                            List<SnapshotMember> members, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.epochId = Objects.requireNonNull(epochId, "epochId");
        this.clusterKey = Objects.requireNonNull(clusterKey, "clusterKey");
        this.policyVersion = Objects.requireNonNull(policyVersion, "policyVersion");
        this.members = List.copyOf(members);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Фиксация кластера при открытии эпохи (минимум один участник). */
    public static ClusterSnapshot fix(UUID epochId, String clusterKey, String policyVersion,
                                      List<ClusterMember> members, Instant now) {
        Objects.requireNonNull(members, "members");
        if (members.isEmpty()) {
            throw new IllegalArgumentException("Кластер фиксируется минимум с одним участником");
        }
        List<SnapshotMember> snapshotMembers = members.stream()
            .map(member -> new SnapshotMember(member.userId(), member.entryId(),
                member.locationVersion()))
            .toList();
        return new ClusterSnapshot(UUID.randomUUID(), epochId, clusterKey, policyVersion,
            snapshotMembers, now);
    }

    /** Восстановление из хранилища (использует только persistence-адаптер). */
    public static ClusterSnapshot restore(UUID id, UUID epochId, String clusterKey,
                                          String policyVersion,
                                          List<SnapshotMember> members, Instant createdAt) {
        return new ClusterSnapshot(id, epochId, clusterKey, policyVersion, members, createdAt);
    }

    public UUID id() {
        return id;
    }

    public UUID epochId() {
        return epochId;
    }

    public String clusterKey() {
        return clusterKey;
    }

    public String policyVersion() {
        return policyVersion;
    }

    public List<SnapshotMember> members() {
        return members;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** Состав кластера: пользователь, участие, версия координат (раздел 11). */
    public record SnapshotMember(UUID userId, UUID entryId, long locationVersion) {
    }
}
