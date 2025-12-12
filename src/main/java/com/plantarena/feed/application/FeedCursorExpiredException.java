package com.plantarena.feed.application;

/** Истёкший курсор ленты (раздел 9): 410 FEED_CURSOR_EXPIRED, начать новую ленту. */
public class FeedCursorExpiredException extends RuntimeException {

    public FeedCursorExpiredException(String message) {
        super(message);
    }
}
