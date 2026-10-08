package com.plantarena.feed.application.port.out;

import java.util.Set;
import java.util.UUID;


public interface VotingDirectory {

    
    Set<UUID> findVotedEntryIdsInOpenWindows(String subjectKey);

    
    Set<UUID> findParticipatedTournamentIds(UUID userId);
}
