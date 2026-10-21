package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.tournaments.domain.CancelReason;
import com.plantarena.tournaments.domain.EliminationAlgorithmKind;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentRepository;
import com.plantarena.tournaments.domain.TournamentStatus;
import com.plantarena.tournaments.domain.TournamentType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;


@Repository
@Transactional
public class JdbcTournamentRepository implements TournamentRepository {

    private static final String SELECT_TOURNAMENT = """
            select id, creator_id, name, description, type, status, algorithm,
                   registration_deadline, round_duration_seconds, elimination_fraction,
                   min_participants, cancel_reason, created_at, version
            from tournaments.tournament
            """;

    private static final String SEARCH_CONDITIONS = """
            t.type = 'PRIVATE'
            and (:admin = true or t.creator_id = :userId
                or exists (select 1 from tournaments.invitation i
                    where i.tournament_id = t.id and i.user_id = :userId
                        and i.status in ('INVITED', 'ACCEPTED_PENDING_MODERATION', 'READY'))
                or exists (select 1 from tournaments.tournament_entry e
                    where e.tournament_id = t.id and e.user_id = :userId))
            and (cast(:status as varchar) is null or t.status = cast(:status as varchar))
            and (cast(:tagId as uuid) is null or exists (select 1 from tournaments.tournament_tag tt
                    where tt.tournament_id = t.id and tt.tag_id = cast(:tagId as uuid)))
            """;

    private final JdbcClient jdbcClient;

    public JdbcTournamentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Tournament save(Tournament tournament) {
        int updated = jdbcClient.sql("""
                update tournaments.tournament
                set name = :name, description = :description, status = :status,
                    registration_deadline = :registrationDeadline,
                    round_duration_seconds = :roundDurationSeconds,
                    elimination_fraction = :eliminationFraction,
                    min_participants = :minParticipants, cancel_reason = :cancelReason,
                    version = version + 1
                where id = :id
                """)
            .param("id", tournament.id())
            .param("name", tournament.name())
            .param("description", tournament.description())
            .param("status", tournament.status().name())
            .param("registrationDeadline", Timestamp.from(tournament.registrationDeadline()))
            .param("roundDurationSeconds", tournament.roundDuration().toSeconds())
            .param("eliminationFraction", tournament.eliminationFraction())
            .param("minParticipants", tournament.minParticipants())
            .param("cancelReason",
                tournament.cancelReason() == null ? null : tournament.cancelReason().name())
            .update();
        if (updated == 0) {
            jdbcClient.sql("""
                    insert into tournaments.tournament (id, creator_id, name, description, type,
                        status, algorithm, registration_deadline, round_duration_seconds,
                        elimination_fraction, min_participants, cancel_reason, created_at, version)
                    values (:id, :creatorId, :name, :description, :type, :status, :algorithm,
                        :registrationDeadline, :roundDurationSeconds, :eliminationFraction,
                        :minParticipants, :cancelReason, :createdAt, 0)
                    """)
                .param("id", tournament.id())
                .param("creatorId", tournament.creatorId())
                .param("name", tournament.name())
                .param("description", tournament.description())
                .param("type", tournament.type().name())
                .param("status", tournament.status().name())
                .param("algorithm", tournament.algorithm().name())
                .param("registrationDeadline", Timestamp.from(tournament.registrationDeadline()))
                .param("roundDurationSeconds", tournament.roundDuration().toSeconds())
                .param("eliminationFraction", tournament.eliminationFraction())
                .param("minParticipants", tournament.minParticipants())
                .param("cancelReason",
                    tournament.cancelReason() == null ? null : tournament.cancelReason().name())
                .param("createdAt", Timestamp.from(tournament.createdAt()))
                .update();
        }
        replaceTags(tournament.id(), tournament.tagIds());
        return findById(tournament.id()).orElseThrow(() -> new IllegalStateException(
            "Турнир не найден после сохранения: " + tournament.id()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Tournament> findById(UUID id) {
        return jdbcClient.sql(SELECT_TOURNAMENT + " where id = :id")
            .param("id", id)
            .query(this::mapTournament)
            .optional();
    }

    @Override
    public void delete(UUID id) {
        jdbcClient.sql("delete from tournaments.tournament_tag where tournament_id = :id")
            .param("id", id)
            .update();
        jdbcClient.sql("delete from tournaments.tournament where id = :id")
            .param("id", id)
            .update();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Tournament> search(TournamentFilter filter) {
        return jdbcClient.sql("select t.id, t.creator_id, t.name, t.description, t.type,"
                + " t.status, t.algorithm, t.registration_deadline, t.round_duration_seconds,"
                + " t.elimination_fraction, t.min_participants, t.cancel_reason, t.created_at,"
                + " t.version from tournaments.tournament t where " + SEARCH_CONDITIONS
                + " order by t.created_at desc, t.id asc limit :limit offset :offset")
            .param("admin", filter.admin())
            .param("userId", filter.userId())
            .param("status", filter.status() == null ? null : filter.status().name())
            .param("tagId", filter.tagId())
            .param("limit", filter.size())
            .param("offset", (long) (filter.offset() / filter.size()) * filter.size())
            .query(this::mapTournament)
            .list();
    }

    @Override
    @Transactional(readOnly = true)
    public long count(TournamentFilter filter) {
        return jdbcClient.sql("select count(*) from tournaments.tournament t where "
                + SEARCH_CONDITIONS)
            .param("admin", filter.admin())
            .param("userId", filter.userId())
            .param("status", filter.status() == null ? null : filter.status().name())
            .param("tagId", filter.tagId())
            .query((rs, rowNum) -> rs.getLong(1))
            .single();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Tournament> findDueForStart(Instant now, int limit) {
        return jdbcClient.sql(SELECT_TOURNAMENT
                + " where status = 'REGISTRATION_OPEN' and registration_deadline <= :now"
                + " order by registration_deadline asc, id asc"
                + " limit :limit")
            .param("now", Timestamp.from(now))
            .param("limit", limit)
            .query(this::mapTournament)
            .list();
    }

    private void replaceTags(UUID tournamentId, Set<UUID> tagIds) {
        jdbcClient.sql("delete from tournaments.tournament_tag where tournament_id = :tournamentId")
            .param("tournamentId", tournamentId)
            .update();
        if (tagIds.isEmpty()) {
            return;
        }
        Set<UUID> existing = jdbcClient.sql("select id from tournaments.tag where id in (:ids)")
            .param("ids", tagIds)
            .query((rs, rowNum) -> rs.getObject("id", UUID.class))
            .set();
        for (UUID tagId : existing) {
            jdbcClient.sql("""
                    insert into tournaments.tournament_tag (tournament_id, tag_id)
                    values (:tournamentId, :tagId)
                    """)
                .param("tournamentId", tournamentId)
                .param("tagId", tagId)
                .update();
        }
    }

    private Tournament mapTournament(ResultSet rs, int rowNum) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        String cancelReason = rs.getString("cancel_reason");
        return Tournament.restore(
            id,
            rs.getObject("creator_id", UUID.class),
            rs.getString("name"),
            rs.getString("description"),
            TournamentType.valueOf(rs.getString("type")),
            TournamentStatus.valueOf(rs.getString("status")),
            EliminationAlgorithmKind.valueOf(rs.getString("algorithm")),
            rs.getTimestamp("registration_deadline").toInstant(),
            Duration.ofSeconds(rs.getLong("round_duration_seconds")),
            rs.getDouble("elimination_fraction"),
            rs.getInt("min_participants"),
            cancelReason == null ? null : CancelReason.valueOf(cancelReason),
            loadTagIds(id),
            rs.getTimestamp("created_at").toInstant(),
            rs.getLong("version"));
    }

    private Set<UUID> loadTagIds(UUID tournamentId) {
        return jdbcClient.sql(
                "select tag_id from tournaments.tournament_tag where tournament_id = :tournamentId")
            .param("tournamentId", tournamentId)
            .query((rs, rowNum) -> rs.getObject("tag_id", UUID.class))
            .set();
    }
}
