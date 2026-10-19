package com.learning.order_service.gateway.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default publisher: prints events, so the service runs without a broker. */
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(String key, String eventType, String payloadJson) {
        log.info("event {} key={} {}", eventType, key, payloadJson);
    }
}
