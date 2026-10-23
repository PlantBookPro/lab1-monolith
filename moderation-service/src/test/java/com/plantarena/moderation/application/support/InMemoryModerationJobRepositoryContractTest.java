package com.plantarena.moderation.application.support;

import com.plantarena.moderation.ModerationJobRepositoryContractTest;
import com.plantarena.moderation.domain.ModerationJobRepository;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Контракт ModerationJobRepository: in-memory фейк")
class InMemoryModerationJobRepositoryContractTest extends ModerationJobRepositoryContractTest {

    private final InMemoryModerationJobRepository repository = new InMemoryModerationJobRepository();

    @Override
    protected ModerationJobRepository repository() {
        return repository;
    }
}
