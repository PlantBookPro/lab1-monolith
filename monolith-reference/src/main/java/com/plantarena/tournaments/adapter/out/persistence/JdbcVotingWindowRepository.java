package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.tournaments.domain.ParticipantResult;
import com.plantarena.tournaments.domain.Vote;
import com.plantarena.tournaments.domain.VoteValue;
import com.plantarena.tournaments.domain.VotingWindow;
import com.plantarena.tournaments.domain.VotingWindowRepository;
import com.plantarena.tournaments.domain.WindowParticipant;
import com.plantarena.tournaments.domain.WindowScope;
import com.plantarena.tournaments.domain.WindowStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;


@Repository
public class JdbcVotingWindowRepository implements VotingWindowRepository {

    private static final String SELECT_WINDOW = """
            select id, tournament_id, sequence, scope, epoch_id, cluster_id, cluster_key, status,
                   opens_at, closes_at, created_at, version
            from tournaments.voting_window
            """;

    private final JdbcClient jdbcClient;

    public JdbcVotingWindowRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional
    public VotingWindow save(VotingWindow window) {
        int updated = jdbcClient.sql("""
                update tournaments.voting_window
                set tournament_id = :tournamentId, sequence = :sequence, scope = :scope,
                    epoch_id = :epochId, cluster_id = :clusterId, cluster_key = :clusterKey,
                    status = :status, opens_at = :opensAt, closes_at = :closesAt,
                    created_at = :createdAt, version = version + 1
                where id = :id
                """)
            .param("id", window.id())
            .param("tournamentId", window.tournamentId())
            .param("sequence", window.sequence())
            .param("scope", window.scope().name())
            .param("epochId", window.epochId())
            .param("clusterId", window.clusterId())
            .param("clusterKey", window.clusterKey())
            .param("status", window.status().name())
            .param("opensAt", Timestamp.from(window.opensAt()))
            .param("closesAt", Timestamp.from(window.closesAt()))
            .param("createdAt", Timestamp.from(window.createdAt()))
            .update();
        if (updated == 0) {
            jdbcClient.sql("""
                    insert into tournaments.voting_window (id, tournament_id, sequence, scope,
                        epoch_id, cluster_id, cluster_key, status, opens_at, closes_at,
                        created_at, version)
                    values (:id, :tournamentId, :sequence, :scope, :epochId, :clusterId,
                        :clusterKey, :status, :opensAt, :closesAt, :createdAt, 0)
                    """)
                .param("id", window.id())
                .param("tournamentId", window.tournamentId())
                .param("sequence", window.sequence())
                .param("scope", window.scope().name())
                .param("epochId", window.epochId())
                .param("clusterId", window.clusterId())
                .param("clusterKey", window.clusterKey())
                .param("status", window.status().name())
                .param("opensAt", Timestamp.from(window.opensAt()))
                .param("closesAt", Timestamp.from(window.closesAt()))
                .param("createdAt", Timestamp.from(window.createdAt()))
                .update();
        }
        replaceParticipants(window);
        return window;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VotingWindow> findById(UUID id) {
        return jdbcClient.sql(SELECT_WINDOW + " where id = :id")
            .param("id", id)
            .query(this::mapWindow)
            .optional();
    }

    @Override
    @Transactional
    public Optional<VotingWindow> findByIdForUpdate(UUID id) {
        return jdbcClient.sql(SELECT_WINDOW + " where id = :id for update")
            .param("id", id)
            .query(this::mapWindow)
            .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findDueForClose(Instant now, int limit) {
        return jdbcClient.sql("select id from tournaments.voting_window"
                + " where status = 'OPEN' and closes_at <= :now"
                + " order by closes_at"
                + " limit :limit")
            .param("now", Timestamp.from(now))
            .param("limit", limit)
            .query((rs, rowNum) -> rs.getObject("id", UUID.class))
            .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<VotingWindow> findByTournamentId(UUID tournamentId, int offset, int size) {
        int pageSize = Math.max(size, 1);
        return jdbcClient.sql(SELECT_WINDOW
                + " where tournament_id = :tournamentId"
                + " order by sequence"
                + " limit :limit offset :offset")
            .param("tournamentId", tournamentId)
            .param("limit", pageSize)
            .param("offset", (long) (offset / pageSize) * pageSize)
            .query(this::mapWindow)
            .list();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByTournamentId(UUID tournamentId) {
        return jdbcClient.sql("select count(*) from tournaments.voting_window"
                + " where tournament_id = :tournamentId")
            .param("tournamentId", tournamentId)
            .query((rs, rowNum) -> rs.getLong(1))
            .single();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VotingWindow> findLatestByTournamentId(UUID tournamentId) {
        return jdbcClient.sql(SELECT_WINDOW
                + " where tournament_id = :tournamentId"
                + " order by sequence desc"
                + " limit 1")
            .param("tournamentId", tournamentId)
            .query(this::mapWindow)
            .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<VotingWindow> findAllByTournamentId(UUID tournamentId) {
        return jdbcClient.sql(SELECT_WINDOW
                + " where tournament_id = :tournamentId"
                + " order by sequence")
            .param("tournamentId", tournamentId)
            .query(this::mapWindow)
            .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findDueForCloseByScope(WindowScope scope, Instant now, int limit) {
        return jdbcClient.sql("select id from tournaments.voting_window"
                + " where scope = :scope and status = 'OPEN' and closes_at <= :now"
                + " order by closes_at"
                + " limit :limit")
            .param("scope", scope.name())
            .param("now", Timestamp.from(now))
            .param("limit", limit)
            .query((rs, rowNum) -> rs.getObject("id", UUID.class))
            .list();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VotingWindow> findOpenByScope(UUID tournamentId, WindowScope scope) {
        return jdbcClient.sql(SELECT_WINDOW
                + " where tournament_id = :tournamentId and scope = :scope and status = :status"
                + " order by sequence desc"
                + " limit 1")
            .param("tournamentId", tournamentId)
            .param("scope", scope.name())
            .param("status", WindowStatus.OPEN.name())
            .query(this::mapWindow)
            .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VotingWindow> findLatestByTournamentIdAndScope(UUID tournamentId,
                                                                   WindowScope scope) {
        return jdbcClient.sql(SELECT_WINDOW
                + " where tournament_id = :tournamentId and scope = :scope"
                + " order by sequence desc"
                + " limit 1")
            .param("tournamentId", tournamentId)
            .param("scope", scope.name())
            .query(this::mapWindow)
            .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<VotingWindow> findOpenByEpochId(UUID epochId) {
        return jdbcClient.sql(SELECT_WINDOW
                + " where epoch_id = :epochId and status = :status"
                + " order by cluster_key asc")
            .param("epochId", epochId)
            .param("status", WindowStatus.OPEN.name())
            .query(this::mapWindow)
            .list();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VotingWindow> findOpenByClusterId(UUID clusterId) {
        return jdbcClient.sql(SELECT_WINDOW
                + " where cluster_id = :clusterId and status = :status")
            .param("clusterId", clusterId)
            .param("status", WindowStatus.OPEN.name())
            .query(this::mapWindow)
            .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public long countOpenByEpochId(UUID epochId) {
        return jdbcClient.sql("select count(*) from tournaments.voting_window"
                + " where epoch_id = :epochId and status = :status")
            .param("epochId", epochId)
            .param("status", WindowStatus.OPEN.name())
            .query((rs, rowNum) -> rs.getLong(1))
            .single();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> findVotedEntryIdsInOpenWindows(String subjectKey) {
        return jdbcClient.sql("""
                select wp.entry_id
                from tournaments.vote v
                join tournaments.window_participant wp on wp.id = v.window_participant_id
                join tournaments.voting_window w on w.id = wp.window_id
                where v.subject_key = :subjectKey and w.status = 'OPEN'
                """)
            .param("subjectKey", subjectKey)
            .query((rs, rowNum) -> rs.getObject("entry_id", UUID.class))
            .set();
    }

    private void replaceParticipants(VotingWindow window) {
        jdbcClient.sql("delete from tournaments.vote where window_participant_id in"
                + " (select id from tournaments.window_participant where window_id = :windowId)")
            .param("windowId", window.id())
            .update();
        jdbcClient.sql("delete from tournaments.window_participant where window_id = :windowId")
            .param("windowId", window.id())
            .update();
        for (WindowParticipant participant : window.participants()) {
            jdbcClient.sql("""
                    insert into tournaments.window_participant (id, window_id, entry_id, user_id,
                        score, result, joined_at)
                    values (:id, :windowId, :entryId, :userId, :score, :result, :joinedAt)
                    """)
                .param("id", participant.id())
                .param("windowId", window.id())
                .param("entryId", participant.entryId())
                .param("userId", participant.userId())
                .param("score", participant.score())
                .param("result", participant.result().name())
                .param("joinedAt", Timestamp.from(participant.joinedAt()))
                .update();
            for (Vote vote : window.votesOf(participant.entryId())) {
                jdbcClient.sql("""
                        insert into tournaments.vote (id, window_participant_id, subject_key,
                            value, created_at, updated_at)
                        values (:id, :participantId, :subjectKey, :value, :createdAt, :updatedAt)
                        """)
                    .param("id", vote.id())
                    .param("participantId", participant.id())
                    .param("subjectKey", vote.subjectKey())
                    .param("value", vote.value().name())
                    .param("createdAt", Timestamp.from(vote.createdAt()))
                    .param("updatedAt", Timestamp.from(vote.updatedAt()))
                    .update();
            }
        }
    }

    private VotingWindow mapWindow(ResultSet rs, int rowNum) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        return VotingWindow.restore(
            id,
            rs.getObject("tournament_id", UUID.class),
            rs.getInt("sequence"),
            WindowScope.valueOf(rs.getString("scope")),
            rs.getObject("epoch_id", UUID.class),
            rs.getObject("cluster_id", UUID.class),
            rs.getString("cluster_key"),
            WindowStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("opens_at").toInstant(),
            rs.getTimestamp("closes_at").toInstant(),
            rs.getTimestamp("created_at").toInstant(),
            rs.getLong("version"),
            loadParticipants(id),
            loadVotes(id));
    }

    private List<WindowParticipant> loadParticipants(UUID windowId) {
        return jdbcClient.sql("""
                select id, entry_id, user_id, score, result, joined_at
                from tournaments.window_participant
                where window_id = :windowId
                order by joined_at, id
                """)
            .param("windowId", windowId)
            .query((rs, rowNum) -> WindowParticipant.restore(
                rs.getObject("id", UUID.class),
                rs.getObject("entry_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getLong("score"),
                ParticipantResult.valueOf(rs.getString("result")),
                rs.getTimestamp("joined_at").toInstant()))
            .list();
    }

    private List<Vote> loadVotes(UUID windowId) {
        return jdbcClient.sql("""
                select v.id, wp.entry_id, v.subject_key, v.value, v.created_at, v.updated_at
                from tournaments.vote v
                join tournaments.window_participant wp on wp.id = v.window_participant_id
                where wp.window_id = :windowId
                order by v.created_at, v.id
                """)
            .param("windowId", windowId)
            .query((rs, rowNum) -> Vote.restore(
                rs.getObject("id", UUID.class),
                rs.getObject("entry_id", UUID.class),
                rs.getString("subject_key"),
                VoteValue.valueOf(rs.getString("value")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()))
            .list();
    }
}
