package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.TagRepositoryContractTest;
import com.plantarena.tournaments.domain.TagRepository;
import com.plantarena.tournaments.domain.TournamentRepository;

/** Фейк честен контракту TagRepository. */
class InMemoryTagRepositoryContractTest extends TagRepositoryContractTest {

    private final InMemoryTournamentRepository tournaments = new InMemoryTournamentRepository();
    private final InMemoryTagRepository tags = new InMemoryTagRepository(tournaments);

    @Override
    protected TagRepository repository() {
        return tags;
    }

    @Override
    protected TournamentRepository tournaments() {
        return tournaments;
    }
}
