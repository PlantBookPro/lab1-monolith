package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.tournaments.domain.EpochStatus;
import com.plantarena.tournaments.domain.QualificationEpoch;
import com.plantarena.tournaments.domain.QualificationEpochRepository;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;


@Repository
public class JdbcQualificationEpochRepository implements QualificationEpochRepository {

    private static final String SELECT_EPOCH = """
            select id, tournament_id, sequence, status, opens_at, closes_at, created_at, version
            from tournaments.qualification_epoch
            """;

    private final JdbcClient jdbcClient;

    public JdbcQualificationEpochRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional
    public QualificationEpoch save(QualificationEpoch epoch) {
        int updated = jdbcClient.sql("""
                update tournaments.qualification_epoch
                set tournament_id = :tournamentId, sequence = :sequence, status = :status,
                    opens_at = :opensAt, closes_at = :closesAt, created_at = :createdAt,
                    version = version + 1
                where id = :id
                """)
            .param("id", epoch.id())
            .param("tournamentId", epoch.tournamentId())
            .param("sequence", epoch.sequence())
            .param("status", epoch.status().name())
            .param("opensAt", Timestamp.from(epoch.opensAt()))
            .param("closesAt", Timestamp.from(epoch.closesAt()))
            .param("createdAt", Timestamp.from(epoch.createdAt()))
            .update();
        if (updated == 0) {
            jdbcClient.sql("""
                    insert into tournaments.qualification_epoch (id, tournament_id, sequence,
                        status, opens_at, closes_at, created_at, version)
                    values (:id, :tournamentId, :sequence, :status, :opensAt, :closesAt,
                        :createdAt, 0)
                    """)
                .param("id", epoch.id())
                .param("tournamentId", epoch.tournamentId())
                .param("sequence", epoch.sequence())
                .param("status", epoch.status().name())
                .param("opensAt", Timestamp.from(epoch.opensAt()))
                .param("closesAt", Timestamp.from(epoch.closesAt()))
                .param("createdAt", Timestamp.from(epoch.createdAt()))
                .update();
        }
        return epoch;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<QualificationEpoch> findById(UUID id) {
        return jdbcClient.sql(SELECT_EPOCH + " where id = :id")
            .param("id", id)
            .query((rs, rowNum) -> QualificationEpoch.restore(
                rs.getObject("id", UUID.class),
                rs.getObject("tournament_id", UUID.class),
                rs.getInt("sequence"),
                EpochStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("opens_at").toInstant(),
                rs.getTimestamp("closes_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getLong("version")))
            .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<QualificationEpoch> findOpenByTournamentId(UUID tournamentId) {
        return findFirstByTournamentIdAndOptionalStatus(tournamentId, EpochStatus.OPEN);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<QualificationEpoch> findLastByTournamentId(UUID tournamentId) {
        return findFirstByTournamentIdAndOptionalStatus(tournamentId, null);
    }

    private Optional<QualificationEpoch> findFirstByTournamentIdAndOptionalStatus(
        UUID tournamentId, EpochStatus status) {
        String sql = SELECT_EPOCH + " where tournament_id = :tournamentId";
        if (status != null) {
            sql += " and status = :status";
        }
        sql += " order by sequence desc limit 1";
        var statement = jdbcClient.sql(sql).param("tournamentId", tournamentId);
        if (status != null) {
            statement = statement.param("status", status.name());
        }
        return statement
            .query((rs, rowNum) -> QualificationEpoch.restore(
                rs.getObject("id", UUID.class),
                rs.getObject("tournament_id", UUID.class),
                rs.getInt("sequence"),
                EpochStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("opens_at").toInstant(),
                rs.getTimestamp("closes_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getLong("version")))
            .optional();
    }
}
