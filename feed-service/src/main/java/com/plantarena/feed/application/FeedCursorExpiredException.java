package com.plantarena.feed.application;


public class FeedCursorExpiredException extends RuntimeException {

    public FeedCursorExpiredException(String message) {
        super(message);
    }
}
