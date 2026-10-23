package com.plantarena.shared.event;

import java.time.Instant;
import java.util.UUID;


public interface IntegrationEvent {

    UUID eventId();

    String eventType();

    int schemaVersion();

    UUID aggregateId();

    long aggregateVersion();

    Instant occurredAt();

    UUID correlationId();
}
