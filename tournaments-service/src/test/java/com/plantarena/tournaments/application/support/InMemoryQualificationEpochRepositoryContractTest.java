package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.QualificationEpochRepositoryContractTest;
import com.plantarena.tournaments.domain.QualificationEpochRepository;
import java.util.UUID;

class InMemoryQualificationEpochRepositoryContractTest
        extends QualificationEpochRepositoryContractTest {

    private final InMemoryQualificationEpochRepository repository =
        new InMemoryQualificationEpochRepository();

    @Override
    protected QualificationEpochRepository repository() {
        return repository;
    }

    @Override
    protected UUID newTournamentId() {
        return UUID.randomUUID();
    }
}
