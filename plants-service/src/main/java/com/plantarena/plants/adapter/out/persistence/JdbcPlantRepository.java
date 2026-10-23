package com.plantarena.plants.adapter.out.persistence;

import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.LifeStatus;
import com.plantarena.plants.domain.ModerationStatus;
import com.plantarena.plants.domain.Plant;
import com.plantarena.plants.domain.PlantRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;


@Repository
@Transactional
@RequiredArgsConstructor
public class JdbcPlantRepository implements PlantRepository {

    private static final String SELECT = """
        SELECT id, owner_id, asset_id, fingerprint, fingerprint_version, title,
               moderation_status, moderation_reason, life_status, died_at,
               archived_at, created_at, version
        FROM plants.plant
        """;

    private final JdbcClient jdbcClient;

    @Override
    public Plant save(Plant plant) {
        int updated = jdbcClient.sql("""
                UPDATE plants.plant
                SET title = :title,
                    moderation_status = :moderationStatus,
                    moderation_reason = :moderationReason,
                    life_status = :lifeStatus,
                    died_at = :diedAt,
                    archived_at = :archivedAt,
                    version = version + 1
                WHERE id = :id
                """)
            .param("title", plant.title())
            .param("moderationStatus", plant.moderationStatus().name())
            .param("moderationReason", plant.moderationReason())
            .param("lifeStatus", plant.lifeStatus().name())
            .param("diedAt", toTimestamp(plant.diedAt()))
            .param("archivedAt", toTimestamp(plant.archivedAt()))
            .param("id", plant.id())
            .update();
        if (updated == 0) {
            jdbcClient.sql("""
                    INSERT INTO plants.plant (id, owner_id, asset_id, fingerprint,
                        fingerprint_version, title, moderation_status, moderation_reason,
                        life_status, died_at, archived_at, created_at, version)
                    VALUES (:id, :ownerId, :assetId, :fingerprint, :fingerprintVersion,
                        :title, :moderationStatus, :moderationReason, :lifeStatus,
                        :diedAt, :archivedAt, :createdAt, 0)
                    """)
                .param("id", plant.id())
                .param("ownerId", plant.ownerId())
                .param("assetId", plant.assetId())
                .param("fingerprint", plant.fingerprint().value())
                .param("fingerprintVersion", plant.fingerprint().algorithmVersion())
                .param("title", plant.title())
                .param("moderationStatus", plant.moderationStatus().name())
                .param("moderationReason", plant.moderationReason())
                .param("lifeStatus", plant.lifeStatus().name())
                .param("diedAt", toTimestamp(plant.diedAt()))
                .param("archivedAt", toTimestamp(plant.archivedAt()))
                .param("createdAt", toTimestamp(plant.createdAt()))
                .update();
        }
        return findById(plant.id()).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Plant> findById(UUID id) {
        return jdbcClient.sql(SELECT + " WHERE id = :id")
            .param("id", id)
            .query((rs, rowNum) -> toDomain(rs))
            .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Plant> findActiveByAssetId(UUID assetId) {
        return jdbcClient.sql(SELECT + " WHERE asset_id = :assetId AND archived_at IS NULL")
            .param("assetId", assetId)
            .query((rs, rowNum) -> toDomain(rs))
            .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Plant> findByOwner(UUID ownerId, int offset, int limit) {
        return jdbcClient.sql(SELECT + """
                WHERE owner_id = :ownerId AND archived_at IS NULL
                ORDER BY id
                LIMIT :limit OFFSET :offset
                """)
            .param("ownerId", ownerId)
            .param("limit", limit)
            .param("offset", offset)
            .query((rs, rowNum) -> toDomain(rs))
            .list();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByOwner(UUID ownerId) {
        return jdbcClient.sql("""
                SELECT count(*)
                FROM plants.plant
                WHERE owner_id = :ownerId AND archived_at IS NULL
                """)
            .param("ownerId", ownerId)
            .query(Long.class)
            .single();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Plant> findApprovedByOwner(UUID ownerId, int offset, int limit) {
        return jdbcClient.sql(SELECT + """
                WHERE owner_id = :ownerId AND archived_at IS NULL
                  AND moderation_status = :moderationStatus
                ORDER BY id
                LIMIT :limit OFFSET :offset
                """)
            .param("ownerId", ownerId)
            .param("moderationStatus", ModerationStatus.APPROVED.name())
            .param("limit", limit)
            .param("offset", offset)
            .query((rs, rowNum) -> toDomain(rs))
            .list();
    }

    @Override
    @Transactional(readOnly = true)
    public long countApprovedByOwner(UUID ownerId) {
        return jdbcClient.sql("""
                SELECT count(*)
                FROM plants.plant
                WHERE owner_id = :ownerId AND archived_at IS NULL
                  AND moderation_status = :moderationStatus
                """)
            .param("ownerId", ownerId)
            .param("moderationStatus", ModerationStatus.APPROVED.name())
            .query(Long.class)
            .single();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Plant> findByModerationStatus(ModerationStatus status, int limit) {
        return jdbcClient.sql(SELECT + """
                WHERE moderation_status = :status
                ORDER BY created_at, id
                LIMIT :limit
                """)
            .param("status", status.name())
            .param("limit", limit)
            .query((rs, rowNum) -> toDomain(rs))
            .list();
    }

    private static Plant toDomain(ResultSet rs) throws SQLException {
        return Plant.restore(rs.getObject("id", UUID.class),
            rs.getObject("owner_id", UUID.class), rs.getObject("asset_id", UUID.class),
            new ImageFingerprint(rs.getString("fingerprint"), rs.getInt("fingerprint_version")),
            rs.getString("title"), ModerationStatus.valueOf(rs.getString("moderation_status")),
            rs.getString("moderation_reason"), LifeStatus.valueOf(rs.getString("life_status")),
            toInstant(rs.getTimestamp("created_at")), toInstant(rs.getTimestamp("died_at")),
            toInstant(rs.getTimestamp("archived_at")), rs.getLong("version"));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
