package com.plantarena.plants.application.support;

import com.plantarena.plants.PlantRepositoryContractTest;
import com.plantarena.plants.domain.PlantRepository;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Контракт PlantRepository: in-memory фейк")
class InMemoryPlantRepositoryContractTest extends PlantRepositoryContractTest {

    private final InMemoryPlantRepository repository = new InMemoryPlantRepository();

    @Override
    protected PlantRepository repository() {
        return repository;
    }
}
