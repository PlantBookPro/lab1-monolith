package com.plantarena.tournaments.domain;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;


public interface TournamentEntryRepository {

    TournamentEntry save(TournamentEntry entry);

    List<TournamentEntry> findByTournamentId(UUID tournamentId, int offset, int size);

    long countByTournamentId(UUID tournamentId);

    
    boolean existsByTournamentIdAndUserId(UUID tournamentId, UUID userId);

    
    Optional<TournamentEntry> findById(UUID id);

    
    Optional<TournamentEntry> findActiveGlobalByUserId(UUID tournamentId, UUID userId);

    
    List<TournamentEntry> findByTournamentIdAndStatus(UUID tournamentId, EntryStatus status);

    
    Set<UUID> findTournamentIdsByUserId(UUID userId);
}
