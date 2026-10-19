package com.learning.order_service.gateway.messaging;

import com.learning.order_service.core.usecase.HandleInventoryEventUsecase;
import org.springframework.kafka.annotation.KafkaListener;

/** Feeds Inventory Service's reservation results into the saga. Failures propagate so the error handler retries, then dead-letters. */
public class InventoryEventListener {

    private final HandleInventoryEventUsecase usecase;

    public InventoryEventListener(HandleInventoryEventUsecase usecase) {
        this.usecase = usecase;
    }

    @KafkaListener(topics = "${order.events.inventory-topic:domain.inventory.events}", groupId = "${spring.kafka.consumer.group-id:order-service}")
    public void onInventoryEvent(String payload) {
        usecase.execute(new HandleInventoryEventUsecase.Request(payload));
    }
}
