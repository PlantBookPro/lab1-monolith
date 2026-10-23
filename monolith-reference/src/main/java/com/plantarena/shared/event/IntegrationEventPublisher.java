package com.plantarena.shared.event;


public interface IntegrationEventPublisher {

    void publish(IntegrationEvent event);
}
