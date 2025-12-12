package com.plantarena.feed.adapter.out.tournaments;

import com.plantarena.feed.application.port.out.VotingDirectory;
import com.plantarena.tournaments.api.FeedDirectory;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** ACL: read-контракт tournaments → порт feed (раздел 4.3, in-process). */
@Component
public class InProcessVotingDirectory implements VotingDirectory {

    private final FeedDirectory feedDirectory;

    public InProcessVotingDirectory(FeedDirectory feedDirectory) {
        this.feedDirectory = feedDirectory;
    }

    @Override
    public Set<UUID> findVotedEntryIdsInOpenWindows(String subjectKey) {
        return feedDirectory.findVotedEntryIdsInOpenWindows(subjectKey);
    }

    @Override
    public Set<UUID> findParticipatedTournamentIds(UUID userId) {
        return feedDirectory.findParticipatedTournamentIds(userId);
    }
}
