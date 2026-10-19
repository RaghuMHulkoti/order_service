package com.learning.order_service.gateway.messaging;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Publishes to one topic, keyed by order id so one order's events stay ordered. */
public class KafkaEventPublisher implements EventPublisher {

    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate, String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void publish(String key, String eventType, String payloadJson) {
        var record = new ProducerRecord<>(topic, key, payloadJson);
        record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        try {
            kafkaTemplate.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing " + eventType, ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RecordTooLargeException || cause instanceof SerializationException
                    || cause instanceof InvalidTopicException) {
                throw new NonRetryablePublishException("Kafka rejected " + eventType + ": " + cause.getMessage(), cause);
            }
            throw new IllegalStateException("Kafka publish failed for " + eventType + ": " + ex.getMessage(), ex);
        } catch (TimeoutException ex) {
            throw new IllegalStateException("Kafka publish timed out for " + eventType, ex);
        }
    }
}
