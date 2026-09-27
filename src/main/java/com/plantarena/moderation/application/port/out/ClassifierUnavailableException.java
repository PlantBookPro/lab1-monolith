package com.plantarena.moderation.application.port.out;

/**
 * Техническая недоступность классификатора (нет модели, сбой инференса,
 * недекодируемый файл) — НЕ решение: задание уходит в RETRY, растение
 * остаётся PENDING (раздел 6, ADR-009).
 */
public class ClassifierUnavailableException extends RuntimeException {

    public ClassifierUnavailableException(String message) {
        super(message);
    }

    public ClassifierUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
