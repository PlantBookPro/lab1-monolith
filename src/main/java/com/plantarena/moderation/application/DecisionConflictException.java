package com.plantarena.moderation.application;

/**
 * Решение по заявке уже зафиксировано (устаревший результат): повтор не
 * применяется, задание завершается DONE с reasonCode=STALE.
 */
public class DecisionConflictException extends RuntimeException {

    public DecisionConflictException(String message) {
        super(message);
    }
}
