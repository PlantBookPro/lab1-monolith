package com.plantarena.plants.application.support;

import com.plantarena.plants.PlantReservationRepositoryContractTest;
import com.plantarena.plants.domain.PlantReservationRepository;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Контракт PlantReservationRepository: in-memory фейк")
class InMemoryPlantReservationRepositoryContractTest extends PlantReservationRepositoryContractTest {

    private final InMemoryPlantReservationRepository repository =
        new InMemoryPlantReservationRepository();

    @Override
    protected PlantReservationRepository repository() {
        return repository;
    }
}
