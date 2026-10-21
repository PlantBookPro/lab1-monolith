package com.plantarena.media.adapter.out.persistence;

import com.plantarena.media.application.port.out.MediaAssetRepository;
import com.plantarena.media.domain.ImageFingerprint;
import com.plantarena.media.domain.ImageFormat;
import com.plantarena.media.domain.MediaAsset;
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
public class JdbcMediaAssetRepository implements MediaAssetRepository {

    private static final String SELECT = """
        SELECT id, owner_id, storage_key, mime_type, byte_size, width, height,
               raw_sha256, image_fingerprint, fingerprint_version, created_at
        FROM media.media_asset
        """;

    private final JdbcClient jdbcClient;

    @Override
    public MediaAsset save(MediaAsset asset) {
        int updated = jdbcClient.sql("""
                UPDATE media.media_asset
                SET owner_id = :ownerId,
                    storage_key = :storageKey,
                    mime_type = :mimeType,
                    byte_size = :byteSize,
                    width = :width,
                    height = :height,
                    raw_sha256 = :rawSha256,
                    image_fingerprint = :imageFingerprint,
                    fingerprint_version = :fingerprintVersion,
                    created_at = :createdAt
                WHERE id = :id
                """)
            .param("ownerId", asset.ownerId())
            .param("storageKey", asset.storageKey())
            .param("mimeType", asset.format().mimeType())
            .param("byteSize", asset.byteSize())
            .param("width", asset.width())
            .param("height", asset.height())
            .param("rawSha256", asset.rawSha256())
            .param("imageFingerprint", asset.fingerprint().value())
            .param("fingerprintVersion", asset.fingerprint().version())
            .param("createdAt", toTimestamp(asset.createdAt()))
            .param("id", asset.id())
            .update();
        if (updated == 0) {
            jdbcClient.sql("""
                    INSERT INTO media.media_asset (id, owner_id, storage_key, mime_type,
                        byte_size, width, height, raw_sha256, image_fingerprint,
                        fingerprint_version, created_at)
                    VALUES (:id, :ownerId, :storageKey, :mimeType, :byteSize, :width, :height,
                        :rawSha256, :imageFingerprint, :fingerprintVersion, :createdAt)
                    """)
                .param("id", asset.id())
                .param("ownerId", asset.ownerId())
                .param("storageKey", asset.storageKey())
                .param("mimeType", asset.format().mimeType())
                .param("byteSize", asset.byteSize())
                .param("width", asset.width())
                .param("height", asset.height())
                .param("rawSha256", asset.rawSha256())
                .param("imageFingerprint", asset.fingerprint().value())
                .param("fingerprintVersion", asset.fingerprint().version())
                .param("createdAt", toTimestamp(asset.createdAt()))
                .update();
        }
        return findById(asset.id()).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MediaAsset> findById(UUID id) {
        return jdbcClient.sql(SELECT + " WHERE id = :id")
            .param("id", id)
            .query((rs, rowNum) -> toDomain(rs))
            .optional();
    }

    @Override
    public void delete(UUID id) {
        jdbcClient.sql("DELETE FROM media.media_asset WHERE id = :id")
            .param("id", id)
            .update();
    }

    private static MediaAsset toDomain(ResultSet rs) throws SQLException {
        return MediaAsset.restore(rs.getObject("id", UUID.class),
            rs.getObject("owner_id", UUID.class), rs.getString("storage_key"),
            ImageFormat.fromMimeType(rs.getString("mime_type")), rs.getLong("byte_size"),
            rs.getInt("width"), rs.getInt("height"), rs.getString("raw_sha256"),
            new ImageFingerprint(rs.getString("image_fingerprint"), rs.getInt("fingerprint_version")),
            toInstant(rs.getTimestamp("created_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
