package com.plantarena.feed.adapter.out.tournaments;

import com.plantarena.feed.application.port.out.VotingDirectory;
import com.plantarena.tournaments.api.FeedDirectory;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class InProcessVotingDirectory implements VotingDirectory {

    private final FeedDirectory feedDirectory;

    @Override
    public Set<UUID> findVotedEntryIdsInOpenWindows(String subjectKey) {
        return feedDirectory.findVotedEntryIdsInOpenWindows(subjectKey);
    }

    @Override
    public Set<UUID> findParticipatedTournamentIds(UUID userId) {
        return feedDirectory.findParticipatedTournamentIds(userId);
    }
}
