package com.plantarena.plants.adapter.out.persistence;

import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.ImageRestriction;
import com.plantarena.plants.domain.ImageRestrictionRepository;
import com.plantarena.plants.domain.RestrictionKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;


@Repository
@Transactional
@RequiredArgsConstructor
public class JdbcImageRestrictionRepository implements ImageRestrictionRepository {

    private static final String SELECT = """
        SELECT id, owner_id, fingerprint, fingerprint_version, kind,
               expires_at, reason, source_entry_id, created_at
        FROM plants.image_restriction
        """;

    private final JdbcClient jdbcClient;

    @Override
    public ImageRestriction save(ImageRestriction restriction) {
        int updated = jdbcClient.sql("""
                UPDATE plants.image_restriction
                SET owner_id = :ownerId,
                    fingerprint = :fingerprint,
                    fingerprint_version = :fingerprintVersion,
                    kind = :kind,
                    expires_at = :expiresAt,
                    reason = :reason,
                    source_entry_id = :sourceEntryId,
                    created_at = :createdAt
                WHERE id = :id
                """)
            .param("ownerId", restriction.ownerId())
            .param("fingerprint", restriction.fingerprint().value())
            .param("fingerprintVersion", restriction.fingerprint().algorithmVersion())
            .param("kind", restriction.kind().name())
            .param("expiresAt", toTimestamp(restriction.expiresAt()))
            .param("reason", restriction.reason())
            .param("sourceEntryId", restriction.sourceEntryId())
            .param("createdAt", toTimestamp(restriction.createdAt()))
            .param("id", restriction.id())
            .update();
        if (updated == 0) {
            jdbcClient.sql("""
                    INSERT INTO plants.image_restriction (id, owner_id, fingerprint,
                        fingerprint_version, kind, expires_at, reason, source_entry_id, created_at)
                    VALUES (:id, :ownerId, :fingerprint, :fingerprintVersion, :kind,
                        :expiresAt, :reason, :sourceEntryId, :createdAt)
                    """)
                .param("id", restriction.id())
                .param("ownerId", restriction.ownerId())
                .param("fingerprint", restriction.fingerprint().value())
                .param("fingerprintVersion", restriction.fingerprint().algorithmVersion())
                .param("kind", restriction.kind().name())
                .param("expiresAt", toTimestamp(restriction.expiresAt()))
                .param("reason", restriction.reason())
                .param("sourceEntryId", restriction.sourceEntryId())
                .param("createdAt", toTimestamp(restriction.createdAt()))
                .update();
        }
        return jdbcClient.sql(SELECT + " WHERE id = :id")
            .param("id", restriction.id())
            .query((rs, rowNum) -> toDomain(rs))
            .optional()
            .orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImageRestriction> findByOwnerAndFingerprint(UUID ownerId, String fingerprintValue) {
        return jdbcClient.sql(SELECT + " WHERE owner_id = :ownerId AND fingerprint = :fingerprint")
            .param("ownerId", ownerId)
            .param("fingerprint", fingerprintValue)
            .query((rs, rowNum) -> toDomain(rs))
            .list();
    }

    private static ImageRestriction toDomain(ResultSet rs) throws SQLException {
        return ImageRestriction.restore(rs.getObject("id", UUID.class),
            rs.getObject("owner_id", UUID.class),
            new ImageFingerprint(rs.getString("fingerprint"), rs.getInt("fingerprint_version")),
            RestrictionKind.valueOf(rs.getString("kind")), toInstant(rs.getTimestamp("expires_at")),
            rs.getString("reason"), rs.getObject("source_entry_id", UUID.class),
            toInstant(rs.getTimestamp("created_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
