package com.plantarena.feed.domain;

import java.util.List;
import java.util.UUID;


public interface FeedCardRepository {

    void saveAll(List<FeedCard> cards);

    void deleteAllByWindowId(UUID windowId);

    
    List<FeedCard> page(FeedCardQuery query);
}
