package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.TournamentRepositoryContractTest;
import com.plantarena.tournaments.domain.TagRepository;
import com.plantarena.tournaments.domain.TournamentRepository;

/** Фейк честен контракту TournamentRepository (раздел 14.2). */
class InMemoryTournamentRepositoryContractTest extends TournamentRepositoryContractTest {

    private final InMemoryTournamentRepository tournaments = new InMemoryTournamentRepository();
    private final InMemoryTagRepository tags = new InMemoryTagRepository(tournaments);

    @Override
    protected TournamentRepository repository() {
        return tournaments;
    }

    @Override
    protected TagRepository tags() {
        return tags;
    }
}
