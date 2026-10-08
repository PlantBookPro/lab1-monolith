package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;


public interface VotingWindowRepository {

    VotingWindow save(VotingWindow window);

    Optional<VotingWindow> findById(UUID id);

    
    Optional<VotingWindow> findByIdForUpdate(UUID id);

    
    List<UUID> findDueForClose(Instant now, int limit);

    List<VotingWindow> findByTournamentId(UUID tournamentId, int offset, int size);

    long countByTournamentId(UUID tournamentId);

    
    Optional<VotingWindow> findLatestByTournamentId(UUID tournamentId);

    
    List<VotingWindow> findAllByTournamentId(UUID tournamentId);

    
    List<UUID> findDueForCloseByScope(WindowScope scope, Instant now, int limit);

    
    Optional<VotingWindow> findOpenByScope(UUID tournamentId, WindowScope scope);

    
    Optional<VotingWindow> findLatestByTournamentIdAndScope(UUID tournamentId, WindowScope scope);

    
    List<VotingWindow> findOpenByEpochId(UUID epochId);

    
    Optional<VotingWindow> findOpenByClusterId(UUID clusterId);

    
    long countOpenByEpochId(UUID epochId);

    
    Set<UUID> findVotedEntryIdsInOpenWindows(String subjectKey);
}
