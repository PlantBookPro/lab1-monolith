package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.InvitationRepository;
import com.plantarena.tournaments.domain.InvitationStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;


@Repository
@Transactional
public class JpaInvitationRepository implements InvitationRepository {

    private final InvitationJpaRepository invitations;
    private final TournamentJpaRepository tournaments;

    public JpaInvitationRepository(InvitationJpaRepository invitations,
                                   TournamentJpaRepository tournaments) {
        this.invitations = invitations;
        this.tournaments = tournaments;
    }

    @Override
    public Invitation save(Invitation invitation) {
        TournamentJpaEntity tournament = tournaments.findById(invitation.tournamentId())
            .orElseThrow(() -> new DataIntegrityViolationException(
                "Турнир приглашения не найден: " + invitation.tournamentId()));
        InvitationJpaEntity entity = invitations.findById(invitation.id())
            .orElseGet(() -> new InvitationJpaEntity(invitation.id(), tournament,
                invitation.userId(), invitation.invitedBy(), invitation.invitedAt()));
        entity.update(invitation.status().name(), invitation.respondedAt(),
            invitation.submittedPlantId(), invitation.reservationId(),
            invitation.submissionKey());
        return toDomain(invitations.saveAndFlush(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Invitation> findById(UUID id) {
        return invitations.findById(id).map(JpaInvitationRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Invitation> findByTournamentIdAndUserId(UUID tournamentId, UUID userId) {
        return invitations.findByTournamentIdAndUserId(tournamentId, userId)
            .map(JpaInvitationRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Invitation> findBySubmittedPlantIdAndStatus(UUID plantId,
                                                                InvitationStatus status) {
        return invitations.findBySubmittedPlantIdAndStatus(plantId, status.name())
            .map(JpaInvitationRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Invitation> findByTournamentId(UUID tournamentId, int offset, int size) {
        return invitations.findByTournamentIdOrderByInvitedAtAscIdAsc(tournamentId,
                PageRequest.of(offset / size, size))
            .stream().map(JpaInvitationRepository::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByTournamentId(UUID tournamentId) {
        return invitations.countByTournamentId(tournamentId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Invitation> findByUserId(UUID userId, int offset, int size) {
        return invitations.findByUserIdOrderByInvitedAtDescIdAsc(userId,
                PageRequest.of(offset / size, size))
            .stream().map(JpaInvitationRepository::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByUserId(UUID userId) {
        return invitations.countByUserId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Invitation> findByTournamentIdAndStatus(UUID tournamentId,
                                                        InvitationStatus status) {
        return invitations.findByTournamentIdAndStatus(tournamentId, status.name()).stream()
            .map(JpaInvitationRepository::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByTournamentId(UUID tournamentId) {
        return invitations.existsByTournamentId(tournamentId);
    }

    private static Invitation toDomain(InvitationJpaEntity entity) {
        return Invitation.restore(entity.getId(), entity.getTournament().getId(),
            entity.getUserId(), entity.getInvitedBy(),
            InvitationStatus.valueOf(entity.getStatus()), entity.getInvitedAt(),
            entity.getRespondedAt(), entity.getSubmittedPlantId(), entity.getReservationId(),
            entity.getSubmissionKey(), entity.getVersion());
    }
}
