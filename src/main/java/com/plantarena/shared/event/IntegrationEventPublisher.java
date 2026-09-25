package com.plantarena.shared.event;

/**
 * Порт публикации опубликованных событий (раздел 10.3): application-слой
 * публикует через порт; in-process реализация на ApplicationEventPublisher
 * (config), подписчики — в adapter.in.events контекстов-потребителей.
 * В лабе №4 заменяется на transactional outbox + Kafka.
 */
public interface IntegrationEventPublisher {

    void publish(IntegrationEvent event);
}
