package com.plantarena.shared.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Маркер опубликованного события (раздел 5 требований): события для других
 * контекстов несут поля будущего конверта уже сейчас. Живёт в shared.event —
 * технический минимум, не доменное понятие (shared не зависит от контекстов).
 */
public interface IntegrationEvent {

    UUID eventId();

    String eventType();

    int schemaVersion();

    UUID aggregateId();

    long aggregateVersion();

    Instant occurredAt();

    UUID correlationId();
}
