package com.learning.order_service.gateway;

import com.learning.order_service.gateway.messaging.EventPublisher;
import com.learning.order_service.gateway.messaging.KafkaEventPublisher;
import com.learning.order_service.gateway.messaging.PaymentEventListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;

import static org.assertj.core.api.Assertions.assertThat;

/** Kafka mode switched on, listener container not started: proves the producer, consumer and dead-letter wiring assemble. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:orderkafka;MODE=Oracle",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.hikari.connection-test-query=SELECT 1",
        "spring.datasource.hikari.connection-init-sql=SELECT 1",
        "spring.flyway.enabled=false",
        "spring.jpa.properties.hibernate.default_schema=PUBLIC",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/migration/V1__create_order_schema.sql,classpath:db/migration/V2__outbox_relay_and_inbox.sql,classpath:db/migration/V3__payment_provider.sql",
        "order.jobs.enabled=false",
        "order.events.publisher=kafka",
        "order.events.consumer-enabled=true",
        "order.events.create-topics=false",
        "spring.kafka.listener.auto-startup=false"
})
class KafkaWiringTest {

    @Autowired
    EventPublisher publisher;
    @Autowired
    PaymentEventListener listener;
    @Autowired
    CommonErrorHandler errorHandler;
    @Autowired
    KafkaListenerEndpointRegistry registry;

    @Test
    void kafkaModeWiresPublisherListenerAndDeadLetterHandler() {
        assertThat(publisher).isInstanceOf(KafkaEventPublisher.class);
        assertThat(errorHandler).isInstanceOf(DefaultErrorHandler.class);
        assertThat(registry.getListenerContainers()).hasSize(2);
        assertThat(registry.getListenerContainers().stream()
                .flatMap(c -> java.util.Arrays.stream(c.getContainerProperties().getTopics())))
                .containsExactlyInAnyOrder("domain.payment.events", "domain.inventory.events");
    }
}
