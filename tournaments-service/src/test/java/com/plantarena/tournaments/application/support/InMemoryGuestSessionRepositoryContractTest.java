package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.GuestSessionRepositoryContractTest;
import com.plantarena.tournaments.domain.GuestSessionRepository;

/** In-memory-наследник контракта GuestSessionRepository. */
class InMemoryGuestSessionRepositoryContractTest extends GuestSessionRepositoryContractTest {

    private final InMemoryGuestSessionRepository repository = new InMemoryGuestSessionRepository();

    @Override
    protected GuestSessionRepository repository() {
        return repository;
    }
}
