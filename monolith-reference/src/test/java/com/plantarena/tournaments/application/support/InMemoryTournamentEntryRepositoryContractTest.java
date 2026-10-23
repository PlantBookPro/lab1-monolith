package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.TournamentEntryRepositoryContractTest;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import com.plantarena.tournaments.domain.TournamentRepository;

/** Фейк честен контракту TournamentEntryRepository. */
class InMemoryTournamentEntryRepositoryContractTest
        extends TournamentEntryRepositoryContractTest {

    private final InMemoryTournamentRepository tournaments = new InMemoryTournamentRepository();
    private final InMemoryTournamentEntryRepository entries =
        new InMemoryTournamentEntryRepository(tournaments);

    @Override
    protected TournamentEntryRepository repository() {
        return entries;
    }

    @Override
    protected TournamentRepository tournaments() {
        return tournaments;
    }
}
