package com.plantarena.media.application.support;

import com.plantarena.media.MediaAssetRepositoryContractTest;
import com.plantarena.media.application.port.out.MediaAssetRepository;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Контракт MediaAssetRepository: in-memory фейк")
class InMemoryMediaAssetRepositoryContractTest extends MediaAssetRepositoryContractTest {

    private final InMemoryMediaAssetRepository repository = new InMemoryMediaAssetRepository();

    @Override
    protected MediaAssetRepository repository() {
        return repository;
    }
}
