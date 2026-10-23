package com.plantarena.feed.application.support;

import com.plantarena.feed.FeedCardRepositoryContractTest;
import com.plantarena.feed.domain.FeedCardRepository;

/** In-memory-наследник контракта FeedCardRepository. */
class InMemoryFeedCardRepositoryContractTest extends FeedCardRepositoryContractTest {

    private final InMemoryFeedCardRepository repository = new InMemoryFeedCardRepository();

    @Override
    protected FeedCardRepository repository() {
        return repository;
    }
}
