package com.plantarena.feed.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA-модель карточки ленты (ADR-002); проекция — без version. */
@Entity
@Table(name = "feed_card", schema = "feed")
public class FeedCardJpaEntity {

    @Id
    private UUID id;

    @Column(name = "window_id", nullable = false)
    private UUID windowId;

    @Column(name = "tournament_id", nullable = false)
    private UUID tournamentId;

    @Column(nullable = false)
    private String scope;

    @Column(name = "cluster_id")
    private UUID clusterId;

    @Column(name = "entry_id", nullable = false)
    private UUID entryId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "plant_id", nullable = false)
    private UUID plantId;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(nullable = false)
    private String title;

    @Column(name = "owner_display_name", nullable = false)
    private String ownerDisplayName;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "closes_at", nullable = false)
    private Instant closesAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    UUID getId() {
        return id;
    }

    UUID getWindowId() {
        return windowId;
    }

    UUID getTournamentId() {
        return tournamentId;
    }

    String getScope() {
        return scope;
    }

    UUID getClusterId() {
        return clusterId;
    }

    UUID getEntryId() {
        return entryId;
    }

    UUID getUserId() {
        return userId;
    }

    UUID getPlantId() {
        return plantId;
    }

    UUID getAssetId() {
        return assetId;
    }

    String getTitle() {
        return title;
    }

    String getOwnerDisplayName() {
        return ownerDisplayName;
    }

    Instant getJoinedAt() {
        return joinedAt;
    }

    Instant getClosesAt() {
        return closesAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    void setId(UUID id) {
        this.id = id;
    }

    void setWindowId(UUID windowId) {
        this.windowId = windowId;
    }

    void setTournamentId(UUID tournamentId) {
        this.tournamentId = tournamentId;
    }

    void setScope(String scope) {
        this.scope = scope;
    }

    void setClusterId(UUID clusterId) {
        this.clusterId = clusterId;
    }

    void setEntryId(UUID entryId) {
        this.entryId = entryId;
    }

    void setUserId(UUID userId) {
        this.userId = userId;
    }

    void setPlantId(UUID plantId) {
        this.plantId = plantId;
    }

    void setAssetId(UUID assetId) {
        this.assetId = assetId;
    }

    void setTitle(String title) {
        this.title = title;
    }

    void setOwnerDisplayName(String ownerDisplayName) {
        this.ownerDisplayName = ownerDisplayName;
    }

    void setJoinedAt(Instant joinedAt) {
        this.joinedAt = joinedAt;
    }

    void setClosesAt(Instant closesAt) {
        this.closesAt = closesAt;
    }

    void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
