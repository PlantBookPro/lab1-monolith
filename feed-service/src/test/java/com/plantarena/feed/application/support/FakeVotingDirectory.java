package com.plantarena.feed.application.support;

import com.plantarena.feed.application.port.out.VotingDirectory;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Фейк read-порта tournaments для тестов FeedService. */
public class FakeVotingDirectory implements VotingDirectory {

    public final Set<UUID> votedEntryIds = new HashSet<>();
    public final Set<UUID> participatedTournamentIds = new HashSet<>();

    @Override
    public Set<UUID> findVotedEntryIdsInOpenWindows(String subjectKey) {
        return votedEntryIds;
    }

    @Override
    public Set<UUID> findParticipatedTournamentIds(UUID userId) {
        return participatedTournamentIds;
    }
}
