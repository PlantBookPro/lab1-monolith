package com.plantarena.tournaments.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;


public interface InvitationRepository {

    Invitation save(Invitation invitation);

    Optional<Invitation> findById(UUID id);

    Optional<Invitation> findByTournamentIdAndUserId(UUID tournamentId, UUID userId);

    
    Optional<Invitation> findBySubmittedPlantIdAndStatus(UUID plantId, InvitationStatus status);

    List<Invitation> findByTournamentId(UUID tournamentId, int offset, int size);

    long countByTournamentId(UUID tournamentId);

    List<Invitation> findByUserId(UUID userId, int offset, int size);

    long countByUserId(UUID userId);

    List<Invitation> findByTournamentIdAndStatus(UUID tournamentId, InvitationStatus status);

    
    boolean existsByTournamentId(UUID tournamentId);
}
