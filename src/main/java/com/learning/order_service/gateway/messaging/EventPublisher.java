package com.learning.order_service.gateway.messaging;

/** Sends one outbox event to the message bus. */
public interface EventPublisher {

    /** @param key partition key; events with the same key stay in order */
    void publish(String key, String eventType, String payloadJson);
}
