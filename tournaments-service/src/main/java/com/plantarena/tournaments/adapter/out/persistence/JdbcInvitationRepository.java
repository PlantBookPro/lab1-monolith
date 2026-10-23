package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.InvitationRepository;
import com.plantarena.tournaments.domain.InvitationStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;


@Repository
@Transactional
public class JdbcInvitationRepository implements InvitationRepository {

    private static final String SELECT_INVITATION = """
            select id, tournament_id, user_id, invited_by, status, invited_at, responded_at,
                   submitted_plant_id, reservation_id, submission_key, version
            from tournaments.invitation
            """;

    private final JdbcClient jdbcClient;

    public JdbcInvitationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Invitation save(Invitation invitation) {
        requireTournament(invitation.tournamentId());
        int updated = jdbcClient.sql("""
                update tournaments.invitation
                set tournament_id = :tournamentId, user_id = :userId, invited_by = :invitedBy,
                    status = :status, invited_at = :invitedAt, responded_at = :respondedAt,
                    submitted_plant_id = :submittedPlantId, reservation_id = :reservationId,
                    submission_key = :submissionKey, version = version + 1
                where id = :id
                """)
            .param("id", invitation.id())
            .param("tournamentId", invitation.tournamentId())
            .param("userId", invitation.userId())
            .param("invitedBy", invitation.invitedBy())
            .param("status", invitation.status().name())
            .param("invitedAt", Timestamp.from(invitation.invitedAt()))
            .param("respondedAt",
                invitation.respondedAt() == null ? null : Timestamp.from(invitation.respondedAt()))
            .param("submittedPlantId", invitation.submittedPlantId())
            .param("reservationId", invitation.reservationId())
            .param("submissionKey", invitation.submissionKey())
            .update();
        if (updated == 0) {
            jdbcClient.sql("""
                    insert into tournaments.invitation (id, tournament_id, user_id, invited_by,
                        status, invited_at, responded_at, submitted_plant_id, reservation_id,
                        submission_key, version)
                    values (:id, :tournamentId, :userId, :invitedBy, :status, :invitedAt,
                        :respondedAt, :submittedPlantId, :reservationId, :submissionKey, 0)
                    """)
                .param("id", invitation.id())
                .param("tournamentId", invitation.tournamentId())
                .param("userId", invitation.userId())
                .param("invitedBy", invitation.invitedBy())
                .param("status", invitation.status().name())
                .param("invitedAt", Timestamp.from(invitation.invitedAt()))
                .param("respondedAt",
                    invitation.respondedAt() == null ? null
                        : Timestamp.from(invitation.respondedAt()))
                .param("submittedPlantId", invitation.submittedPlantId())
                .param("reservationId", invitation.reservationId())
                .param("submissionKey", invitation.submissionKey())
                .update();
        }
        return findById(invitation.id()).orElseThrow(() -> new IllegalStateException(
            "Приглашение не найдено после сохранения: " + invitation.id()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Invitation> findById(UUID id) {
        return jdbcClient.sql(SELECT_INVITATION + " where id = :id")
            .param("id", id)
            .query(this::mapInvitation)
            .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Invitation> findByTournamentIdAndUserId(UUID tournamentId, UUID userId) {
        return jdbcClient.sql(SELECT_INVITATION
                + " where tournament_id = :tournamentId and user_id = :userId")
            .param("tournamentId", tournamentId)
            .param("userId", userId)
            .query(this::mapInvitation)
            .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Invitation> findBySubmittedPlantIdAndStatus(UUID plantId,
                                                                InvitationStatus status) {
        return jdbcClient.sql(SELECT_INVITATION
                + " where submitted_plant_id = :plantId and status = :status")
            .param("plantId", plantId)
            .param("status", status.name())
            .query(this::mapInvitation)
            .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Invitation> findByTournamentId(UUID tournamentId, int offset, int size) {
        return jdbcClient.sql(SELECT_INVITATION
                + " where tournament_id = :tournamentId"
                + " order by invited_at asc, id asc"
                + " limit :limit offset :offset")
            .param("tournamentId", tournamentId)
            .param("limit", size)
            .param("offset", (long) (offset / size) * size)
            .query(this::mapInvitation)
            .list();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByTournamentId(UUID tournamentId) {
        return jdbcClient.sql("select count(*) from tournaments.invitation"
                + " where tournament_id = :tournamentId")
            .param("tournamentId", tournamentId)
            .query((rs, rowNum) -> rs.getLong(1))
            .single();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Invitation> findByUserId(UUID userId, int offset, int size) {
        return jdbcClient.sql(SELECT_INVITATION
                + " where user_id = :userId"
                + " order by invited_at desc, id asc"
                + " limit :limit offset :offset")
            .param("userId", userId)
            .param("limit", size)
            .param("offset", (long) (offset / size) * size)
            .query(this::mapInvitation)
            .list();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByUserId(UUID userId) {
        return jdbcClient.sql("select count(*) from tournaments.invitation"
                + " where user_id = :userId")
            .param("userId", userId)
            .query((rs, rowNum) -> rs.getLong(1))
            .single();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Invitation> findByTournamentIdAndStatus(UUID tournamentId,
                                                        InvitationStatus status) {
        return jdbcClient.sql(SELECT_INVITATION
                + " where tournament_id = :tournamentId and status = :status")
            .param("tournamentId", tournamentId)
            .param("status", status.name())
            .query(this::mapInvitation)
            .list();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByTournamentId(UUID tournamentId) {
        return jdbcClient.sql("select exists(select 1 from tournaments.invitation"
                + " where tournament_id = :tournamentId)")
            .param("tournamentId", tournamentId)
            .query((rs, rowNum) -> rs.getBoolean(1))
            .single();
    }

    private void requireTournament(UUID tournamentId) {
        boolean exists = jdbcClient.sql(
                "select exists(select 1 from tournaments.tournament where id = :id)")
            .param("id", tournamentId)
            .query((rs, rowNum) -> rs.getBoolean(1))
            .single();
        if (!exists) {
            throw new DataIntegrityViolationException(
                "Турнир приглашения не найден: " + tournamentId);
        }
    }

    private Invitation mapInvitation(ResultSet rs, int rowNum) throws SQLException {
        Timestamp respondedAt = rs.getTimestamp("responded_at");
        return Invitation.restore(
            rs.getObject("id", UUID.class),
            rs.getObject("tournament_id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getObject("invited_by", UUID.class),
            InvitationStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("invited_at").toInstant(),
            respondedAt == null ? null : respondedAt.toInstant(),
            rs.getObject("submitted_plant_id", UUID.class),
            rs.getObject("reservation_id", UUID.class),
            rs.getObject("submission_key", UUID.class),
            rs.getLong("version"));
    }
}
