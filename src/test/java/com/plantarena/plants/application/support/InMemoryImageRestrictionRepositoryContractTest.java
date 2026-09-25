package com.plantarena.plants.application.support;

import com.plantarena.plants.ImageRestrictionRepositoryContractTest;
import com.plantarena.plants.domain.ImageRestrictionRepository;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Контракт ImageRestrictionRepository: in-memory фейк")
class InMemoryImageRestrictionRepositoryContractTest extends ImageRestrictionRepositoryContractTest {

    private final InMemoryImageRestrictionRepository repository =
        new InMemoryImageRestrictionRepository();

    @Override
    protected ImageRestrictionRepository repository() {
        return repository;
    }
}
