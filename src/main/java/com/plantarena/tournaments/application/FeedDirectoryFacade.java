package com.plantarena.tournaments.application;

import com.plantarena.tournaments.api.FeedDirectory;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import com.plantarena.tournaments.domain.VotingWindowRepository;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class FeedDirectoryFacade implements FeedDirectory {

    private final VotingWindowRepository windows;
    private final TournamentEntryRepository entries;

    public FeedDirectoryFacade(VotingWindowRepository windows,
                               TournamentEntryRepository entries) {
        this.windows = windows;
        this.entries = entries;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> findVotedEntryIdsInOpenWindows(String subjectKey) {
        return windows.findVotedEntryIdsInOpenWindows(subjectKey);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> findParticipatedTournamentIds(UUID userId) {
        return entries.findTournamentIdsByUserId(userId);
    }
}
