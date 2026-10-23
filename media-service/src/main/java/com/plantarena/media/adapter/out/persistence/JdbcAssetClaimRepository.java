package com.plantarena.media.adapter.out.persistence;

import com.plantarena.media.application.port.out.AssetClaimRepository;
import com.plantarena.media.domain.AssetClaim;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;


@Repository
@Transactional
@RequiredArgsConstructor
public class JdbcAssetClaimRepository implements AssetClaimRepository {

    private static final String SELECT = """
        SELECT asset_id, plant_id, publicly_visible, claimed_at
        FROM media.asset_claim
        """;

    private final JdbcClient jdbcClient;

    @Override
    @Transactional(readOnly = true)
    public Optional<AssetClaim> findByAssetId(UUID assetId) {
        return jdbcClient.sql(SELECT + " WHERE asset_id = :assetId")
            .param("assetId", assetId)
            .query((rs, rowNum) -> toDomain(rs))
            .optional();
    }

    @Override
    public AssetClaim save(AssetClaim claim) {
        int updated = jdbcClient.sql("""
                UPDATE media.asset_claim
                SET plant_id = :plantId,
                    publicly_visible = :publiclyVisible,
                    claimed_at = :claimedAt
                WHERE asset_id = :assetId
                """)
            .param("plantId", claim.plantId())
            .param("publiclyVisible", claim.publiclyVisible())
            .param("claimedAt", toTimestamp(claim.claimedAt()))
            .param("assetId", claim.assetId())
            .update();
        if (updated == 0) {
            jdbcClient.sql("""
                    INSERT INTO media.asset_claim (asset_id, plant_id, publicly_visible, claimed_at)
                    VALUES (:assetId, :plantId, :publiclyVisible, :claimedAt)
                    """)
                .param("assetId", claim.assetId())
                .param("plantId", claim.plantId())
                .param("publiclyVisible", claim.publiclyVisible())
                .param("claimedAt", toTimestamp(claim.claimedAt()))
                .update();
        }
        return findByAssetId(claim.assetId()).orElseThrow();
    }

    @Override
    public void deleteByAssetId(UUID assetId) {
        jdbcClient.sql("DELETE FROM media.asset_claim WHERE asset_id = :assetId")
            .param("assetId", assetId)
            .update();
    }

    private static AssetClaim toDomain(ResultSet rs) throws SQLException {
        return AssetClaim.claimed(rs.getObject("asset_id", UUID.class),
            rs.getObject("plant_id", UUID.class), rs.getBoolean("publicly_visible"),
            toInstant(rs.getTimestamp("claimed_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
