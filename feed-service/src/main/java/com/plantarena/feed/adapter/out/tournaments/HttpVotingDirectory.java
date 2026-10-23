package com.plantarena.feed.adapter.out.tournaments;

import com.plantarena.feed.api.client.TournamentsDirectoryClient;
import com.plantarena.feed.application.port.out.VotingDirectory;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HttpVotingDirectory implements VotingDirectory {

    private final TournamentsDirectoryClient tournamentsClient;

    @Override
    public Set<UUID> findVotedEntryIdsInOpenWindows(String subjectKey) {
        return tournamentsClient.votedEntryIds(subjectKey);
    }

    @Override
    public Set<UUID> findParticipatedTournamentIds(UUID userId) {
        return tournamentsClient.participatedTournamentIds(userId);
    }
}
