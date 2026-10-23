package com.plantarena.identity.application.support;

import com.plantarena.identity.UserRepositoryContractTest;
import com.plantarena.identity.domain.UserRepository;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Контракт UserRepository: in-memory fake")
class InMemoryUserRepositoryContractTest extends UserRepositoryContractTest {

    private final InMemoryUserRepository repository = new InMemoryUserRepository();

    @Override
    protected UserRepository repository() {
        return repository;
    }
}
