package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.tournaments.domain.EntryStatus;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;


@Repository
@Transactional
public class JdbcTournamentEntryRepository implements TournamentEntryRepository {

    private static final String SELECT_ENTRY = """
            select id, tournament_id, user_id, plant_id, reservation_id, status, joined_at
            from tournaments.tournament_entry
            """;

    private final JdbcClient jdbcClient;

    public JdbcTournamentEntryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public TournamentEntry save(TournamentEntry entry) {
        requireTournament(entry.tournamentId());
        int updated = jdbcClient.sql(
                "update tournaments.tournament_entry set status = :status where id = :id")
            .param("id", entry.id())
            .param("status", entry.status().name())
            .update();
        if (updated == 0) {
            jdbcClient.sql("""
                    insert into tournaments.tournament_entry (id, tournament_id, user_id,
                        plant_id, reservation_id, status, joined_at)
                    values (:id, :tournamentId, :userId, :plantId, :reservationId, :status,
                        :joinedAt)
                    """)
                .param("id", entry.id())
                .param("tournamentId", entry.tournamentId())
                .param("userId", entry.userId())
                .param("plantId", entry.plantId())
                .param("reservationId", entry.reservationId())
                .param("status", entry.status().name())
                .param("joinedAt", Timestamp.from(entry.joinedAt()))
                .update();
        }
        return TournamentEntry.restore(entry.id(), entry.tournamentId(), entry.userId(),
            entry.plantId(), entry.reservationId(), entry.status(), entry.joinedAt());
    }

    @Override
    @Transactional(readOnly = true)
    public List<TournamentEntry> findByTournamentId(UUID tournamentId, int offset, int size) {
        return jdbcClient.sql(SELECT_ENTRY
                + " where tournament_id = :tournamentId"
                + " order by joined_at asc, id asc"
                + " limit :limit offset :offset")
            .param("tournamentId", tournamentId)
            .param("limit", size)
            .param("offset", (long) (offset / size) * size)
            .query(this::mapEntry)
            .list();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByTournamentId(UUID tournamentId) {
        return jdbcClient.sql("select count(*) from tournaments.tournament_entry"
                + " where tournament_id = :tournamentId")
            .param("tournamentId", tournamentId)
            .query((rs, rowNum) -> rs.getLong(1))
            .single();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByTournamentIdAndUserId(UUID tournamentId, UUID userId) {
        return jdbcClient.sql("select exists(select 1 from tournaments.tournament_entry"
                + " where tournament_id = :tournamentId and user_id = :userId)")
            .param("tournamentId", tournamentId)
            .param("userId", userId)
            .query((rs, rowNum) -> rs.getBoolean(1))
            .single();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TournamentEntry> findById(UUID id) {
        return jdbcClient.sql(SELECT_ENTRY + " where id = :id")
            .param("id", id)
            .query(this::mapEntry)
            .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TournamentEntry> findActiveGlobalByUserId(UUID tournamentId, UUID userId) {
        return jdbcClient.sql(SELECT_ENTRY
                + " where tournament_id = :tournamentId and user_id = :userId"
                + " and status in ('QUEUED', 'QUALIFYING', 'FINAL_PENDING', 'FINALIST')")
            .param("tournamentId", tournamentId)
            .param("userId", userId)
            .query(this::mapEntry)
            .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TournamentEntry> findByTournamentIdAndStatus(UUID tournamentId,
                                                             EntryStatus status) {
        return jdbcClient.sql(SELECT_ENTRY
                + " where tournament_id = :tournamentId and status = :status")
            .param("tournamentId", tournamentId)
            .param("status", status.name())
            .query(this::mapEntry)
            .list();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> findTournamentIdsByUserId(UUID userId) {
        return jdbcClient.sql(
                "select distinct tournament_id from tournaments.tournament_entry"
                    + " where user_id = :userId")
            .param("userId", userId)
            .query((rs, rowNum) -> rs.getObject("tournament_id", UUID.class))
            .set();
    }

    private void requireTournament(UUID tournamentId) {
        boolean exists = jdbcClient.sql(
                "select exists(select 1 from tournaments.tournament where id = :id)")
            .param("id", tournamentId)
            .query((rs, rowNum) -> rs.getBoolean(1))
            .single();
        if (!exists) {
            throw new DataIntegrityViolationException(
                "Турнир участия не найден: " + tournamentId);
        }
    }

    private TournamentEntry mapEntry(ResultSet rs, int rowNum) throws SQLException {
        return TournamentEntry.restore(
            rs.getObject("id", UUID.class),
            rs.getObject("tournament_id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getObject("plant_id", UUID.class),
            rs.getObject("reservation_id", UUID.class),
            EntryStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("joined_at").toInstant());
    }
}
