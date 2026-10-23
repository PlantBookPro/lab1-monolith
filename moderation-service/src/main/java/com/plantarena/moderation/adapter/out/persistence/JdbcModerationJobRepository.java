package com.plantarena.moderation.adapter.out.persistence;

import com.plantarena.moderation.domain.ModerationJob;
import com.plantarena.moderation.domain.ModerationJobRepository;
import com.plantarena.moderation.domain.ModerationJobStatus;
import com.plantarena.moderation.domain.ModerationReasonCode;
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
public class JdbcModerationJobRepository implements ModerationJobRepository {

    private static final List<String> DUE_STATUSES = List.of("NEW", "RETRY");

    private static final String SELECT = """
        SELECT id, plant_id, asset_id, status, attempts, next_attempt_at, model_version,
               confidence, reason_code, started_at, completed_at, created_at, version
        FROM moderation.moderation_job
        """;

    private final JdbcClient jdbcClient;

    @Override
    public ModerationJob save(ModerationJob job) {
        int updated = jdbcClient.sql("""
                UPDATE moderation.moderation_job
                SET status = :status,
                    attempts = :attempts,
                    next_attempt_at = :nextAttemptAt,
                    model_version = :modelVersion,
                    confidence = :confidence,
                    reason_code = :reasonCode,
                    started_at = :startedAt,
                    completed_at = :completedAt,
                    version = version + 1
                WHERE id = :id
                """)
            .param("status", job.status().name())
            .param("attempts", job.attempts())
            .param("nextAttemptAt", toTimestamp(job.nextAttemptAt()))
            .param("modelVersion", job.modelVersion())
            .param("confidence", job.confidence())
            .param("reasonCode", job.reasonCode() == null ? null : job.reasonCode().name())
            .param("startedAt", toTimestamp(job.startedAt()))
            .param("completedAt", toTimestamp(job.completedAt()))
            .param("id", job.id())
            .update();
        if (updated == 0) {
            jdbcClient.sql("""
                    INSERT INTO moderation.moderation_job (id, plant_id, asset_id, status,
                        attempts, next_attempt_at, model_version, confidence, reason_code,
                        started_at, completed_at, created_at, version)
                    VALUES (:id, :plantId, :assetId, :status, :attempts, :nextAttemptAt,
                        :modelVersion, :confidence, :reasonCode, :startedAt, :completedAt,
                        :createdAt, 0)
                    """)
                .param("id", job.id())
                .param("plantId", job.plantId())
                .param("assetId", job.assetId())
                .param("status", job.status().name())
                .param("attempts", job.attempts())
                .param("nextAttemptAt", toTimestamp(job.nextAttemptAt()))
                .param("modelVersion", job.modelVersion())
                .param("confidence", job.confidence())
                .param("reasonCode", job.reasonCode() == null ? null : job.reasonCode().name())
                .param("startedAt", toTimestamp(job.startedAt()))
                .param("completedAt", toTimestamp(job.completedAt()))
                .param("createdAt", toTimestamp(job.createdAt()))
                .update();
        }
        return findById(job.id()).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ModerationJob> findById(UUID id) {
        return jdbcClient.sql(SELECT + " WHERE id = :id")
            .param("id", id)
            .query((rs, rowNum) -> toDomain(rs))
            .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ModerationJob> findDue(Instant now, int limit) {
        return jdbcClient.sql(SELECT + """
                WHERE status IN (:statuses) AND next_attempt_at <= :now
                ORDER BY next_attempt_at, id
                LIMIT :limit
                """)
            .param("statuses", DUE_STATUSES)
            .param("now", toTimestamp(now))
            .param("limit", limit)
            .query((rs, rowNum) -> toDomain(rs))
            .list();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ModerationJob> findLatestByPlantId(UUID plantId) {
        return jdbcClient.sql(SELECT + """
                WHERE plant_id = :plantId
                ORDER BY created_at DESC
                LIMIT 1
                """)
            .param("plantId", plantId)
            .query((rs, rowNum) -> toDomain(rs))
            .optional();
    }

    private static ModerationJob toDomain(ResultSet rs) throws SQLException {
        float confidence = rs.getFloat("confidence");
        boolean confidenceAbsent = rs.wasNull();
        String reasonCode = rs.getString("reason_code");
        return ModerationJob.restore(rs.getObject("id", UUID.class),
            rs.getObject("plant_id", UUID.class), rs.getObject("asset_id", UUID.class),
            ModerationJobStatus.valueOf(rs.getString("status")), rs.getInt("attempts"),
            toInstant(rs.getTimestamp("next_attempt_at")), rs.getString("model_version"),
            confidenceAbsent ? null : confidence,
            reasonCode == null ? null : ModerationReasonCode.valueOf(reasonCode),
            toInstant(rs.getTimestamp("started_at")), toInstant(rs.getTimestamp("completed_at")),
            toInstant(rs.getTimestamp("created_at")), rs.getLong("version"));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
