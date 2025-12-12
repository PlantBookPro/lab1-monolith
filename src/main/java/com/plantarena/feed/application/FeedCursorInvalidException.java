package com.plantarena.feed.application;

/** Невалидный/подменённый курсор ленты (раздел 9): 400 FEED_CURSOR_INVALID. */
public class FeedCursorInvalidException extends RuntimeException {

    public FeedCursorInvalidException(String message) {
        super(message);
    }
}
