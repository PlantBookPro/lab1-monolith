package com.plantarena.tournaments.application;

import java.time.Instant;

/**
 * Растение не проходит проверки допуска plants (не владелец/погибло/запрет
 * изображения/не APPROVED) — перевод PlantNotEligibleException из ACL
 * (раздел 13: 409; retryAt — для временного запрета изображения).
 */
public class PlantNotReservableException extends RuntimeException {

    private final Instant retryAt;

    public PlantNotReservableException(String message, Instant retryAt) {
        super(message);
        this.retryAt = retryAt;
    }

    public Instant retryAt() {
        return retryAt;
    }
}
