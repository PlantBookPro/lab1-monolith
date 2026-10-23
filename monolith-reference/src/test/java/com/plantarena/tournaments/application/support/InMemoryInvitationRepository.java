package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.InvitationRepository;
import com.plantarena.tournaments.domain.InvitationStatus;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * In-memory фейк InvitationRepository: честен к UNIQUE(tournamentId, userId)
 * (дубль — DataIntegrityViolationException, как БД).
 */
public class InMemoryInvitationRepository implements InvitationRepository {

    public final Map<UUID, Invitation> invitations = new ConcurrentHashMap<>();
    private final InMemoryTournamentRepository tournaments;

    public InMemoryInvitationRepository() {
        this(null);
    }

    public InMemoryInvitationRepository(InMemoryTournamentRepository tournaments) {
        this.tournaments = tournaments;
    }

    @Override
    public Invitation save(Invitation invitation) {
        invitations.values().stream()
            .filter(existing -> existing.tournamentId().equals(invitation.tournamentId())
                && existing.userId().equals(invitation.userId())
                && !existing.id().equals(invitation.id()))
            .findAny()
            .ifPresent(existing -> {
                throw new DataIntegrityViolationException(
                    "Приглашение пары уже существует: " + existing.id());
            });
        invitations.put(invitation.id(), invitation);
        if (tournaments != null) {
            tournaments.trackInvitation(invitation.tournamentId(), invitation.userId(),
                invitation.status());
        }
        return invitation;
    }

    @Override
    public Optional<Invitation> findById(UUID id) {
        return Optional.ofNullable(invitations.get(id));
    }

    @Override
    public Optional<Invitation> findByTournamentIdAndUserId(UUID tournamentId, UUID userId) {
        return invitations.values().stream()
            .filter(invitation -> invitation.tournamentId().equals(tournamentId)
                && invitation.userId().equals(userId))
            .findFirst();
    }

    @Override
    public Optional<Invitation> findBySubmittedPlantIdAndStatus(UUID plantId,
                                                                InvitationStatus status) {
        return invitations.values().stream()
            .filter(invitation -> status.equals(invitation.status())
                && plantId.equals(invitation.submittedPlantId()))
            .findFirst();
    }

    @Override
    public List<Invitation> findByTournamentId(UUID tournamentId, int offset, int size) {
        return invitations.values().stream()
            .filter(invitation -> invitation.tournamentId().equals(tournamentId))
            .sorted(Comparator.comparing(Invitation::id))
            .skip(offset).limit(size).toList();
    }

    @Override
    public long countByTournamentId(UUID tournamentId) {
        return invitations.values().stream()
            .filter(invitation -> invitation.tournamentId().equals(tournamentId))
            .count();
    }

    @Override
    public List<Invitation> findByUserId(UUID userId, int offset, int size) {
        return invitations.values().stream()
            .filter(invitation -> invitation.userId().equals(userId))
            .sorted(Comparator.comparing(Invitation::id))
            .skip(offset).limit(size).toList();
    }

    @Override
    public long countByUserId(UUID userId) {
        return invitations.values().stream()
            .filter(invitation -> invitation.userId().equals(userId))
            .count();
    }

    @Override
    public List<Invitation> findByTournamentIdAndStatus(UUID tournamentId,
                                                        InvitationStatus status) {
        return invitations.values().stream()
            .filter(invitation -> invitation.tournamentId().equals(tournamentId)
                && invitation.status() == status)
            .sorted(Comparator.comparing(Invitation::id))
            .toList();
    }

    @Override
    public boolean existsByTournamentId(UUID tournamentId) {
        return invitations.values().stream()
            .anyMatch(invitation -> invitation.tournamentId().equals(tournamentId));
    }
}
