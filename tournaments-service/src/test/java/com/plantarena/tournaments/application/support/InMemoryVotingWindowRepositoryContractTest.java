package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.VotingWindowRepositoryContractTest;
import com.plantarena.tournaments.domain.VotingWindowRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;

@DisplayName("In-memory фейк VotingWindowRepository (контракт)")
class InMemoryVotingWindowRepositoryContractTest extends VotingWindowRepositoryContractTest {

    private final InMemoryVotingWindowRepository repository = new InMemoryVotingWindowRepository();

    @Override
    protected VotingWindowRepository repository() {
        return repository;
    }

    @Override
    protected UUID newTournamentId() {
        return UUID.randomUUID();
    }
}
