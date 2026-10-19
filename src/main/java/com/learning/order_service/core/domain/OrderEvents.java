package com.learning.order_service.core.domain;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Event envelope of TDD §13.3, shared by everything this service writes to its outbox. */
public final class OrderEvents {

    private static final String PRODUCER = "order-service";

    private OrderEvents() {
    }

    public static String envelope(JsonMapper json, String type, Instant at, Map<String, Object> data) {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", type);
        envelope.put("occurredAt", at.toString());
        envelope.put("producer", PRODUCER);
        envelope.put("traceId", Tracing.current());
        envelope.put("version", 1);
        envelope.put("data", data);
        return json.writeValueAsString(envelope);
    }
}
