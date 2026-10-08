package com.plantarena.feed.domain;

import java.security.SecureRandom;


public class FeedOrdering {

    private final SecureRandom random = new SecureRandom();

    
    public long newSeed() {
        return random.nextLong();
    }
}
