package com.plantarena.tournaments.api;

import java.util.Set;
import java.util.UUID;


public interface FeedDirectory {

    
    Set<UUID> findVotedEntryIdsInOpenWindows(String subjectKey);

    
    Set<UUID> findParticipatedTournamentIds(UUID userId);
}
