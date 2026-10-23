package com.plantarena.plants.adapter.out.persistence;

import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.PlantReservation;
import com.plantarena.plants.domain.PlantReservationRepository;
import com.plantarena.plants.domain.ReservationStatus;
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
public class JdbcPlantReservationRepository implements PlantReservationRepository {

    private static final String SELECT = """
        SELECT id, owner_id, plant_id, fingerprint, fingerprint_version,
               idempotency_key, status, created_at, released_at
        FROM plants.plant_reservation
        """;

    private final JdbcClient jdbcClient;

    @Override
    public PlantReservation save(PlantReservation reservation) {
        int updated = jdbcClient.sql("""
                UPDATE plants.plant_reservation
                SET status = :status,
                    released_at = :releasedAt
                WHERE id = :id
                """)
            .param("status", reservation.status().name())
            .param("releasedAt", toTimestamp(reservation.releasedAt()))
            .param("id", reservation.id())
            .update();
        if (updated == 0) {
            jdbcClient.sql("""
                    INSERT INTO plants.plant_reservation (id, owner_id, plant_id, fingerprint,
                        fingerprint_version, idempotency_key, status, created_at, released_at)
                    VALUES (:id, :ownerId, :plantId, :fingerprint, :fingerprintVersion,
                        :idempotencyKey, :status, :createdAt, :releasedAt)
                    """)
                .param("id", reservation.id())
                .param("ownerId", reservation.ownerId())
                .param("plantId", reservation.plantId())
                .param("fingerprint", reservation.fingerprint().value())
                .param("fingerprintVersion", reservation.fingerprint().algorithmVersion())
                .param("idempotencyKey", reservation.idempotencyKey())
                .param("status", reservation.status().name())
                .param("createdAt", toTimestamp(reservation.createdAt()))
                .param("releasedAt", toTimestamp(reservation.releasedAt()))
                .update();
        }
        return findById(reservation.id()).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlantReservation> findById(UUID id) {
        return jdbcClient.sql(SELECT + " WHERE id = :id")
            .param("id", id)
            .query((rs, rowNum) -> toDomain(rs))
            .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlantReservation> findByIdempotencyKey(UUID idempotencyKey) {
        return jdbcClient.sql(SELECT + " WHERE idempotency_key = :idempotencyKey")
            .param("idempotencyKey", idempotencyKey)
            .query((rs, rowNum) -> toDomain(rs))
            .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlantReservation> findActiveByOwnerAndFingerprint(UUID ownerId,
                                                                      String fingerprintValue) {
        return jdbcClient.sql(SELECT + """
                WHERE owner_id = :ownerId AND fingerprint = :fingerprint AND status = :status
                LIMIT 1
                """)
            .param("ownerId", ownerId)
            .param("fingerprint", fingerprintValue)
            .param("status", ReservationStatus.ACTIVE.name())
            .query((rs, rowNum) -> toDomain(rs))
            .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlantReservation> findActiveByPlantId(UUID plantId) {
        return jdbcClient.sql(SELECT + """
                WHERE plant_id = :plantId AND status = :status
                LIMIT 1
                """)
            .param("plantId", plantId)
            .param("status", ReservationStatus.ACTIVE.name())
            .query((rs, rowNum) -> toDomain(rs))
            .optional();
    }

    private static PlantReservation toDomain(ResultSet rs) throws SQLException {
        return PlantReservation.restore(rs.getObject("id", UUID.class),
            rs.getObject("owner_id", UUID.class), rs.getObject("plant_id", UUID.class),
            new ImageFingerprint(rs.getString("fingerprint"), rs.getInt("fingerprint_version")),
            rs.getObject("idempotency_key", UUID.class),
            ReservationStatus.valueOf(rs.getString("status")),
            toInstant(rs.getTimestamp("created_at")), toInstant(rs.getTimestamp("released_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
