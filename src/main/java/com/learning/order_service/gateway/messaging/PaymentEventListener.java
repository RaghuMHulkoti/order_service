package com.learning.order_service.gateway.messaging;

import com.learning.order_service.core.usecase.HandlePaymentEventUsecase;
import org.springframework.kafka.annotation.KafkaListener;

/** Feeds Payment Service's events into the saga. Failures propagate so the error handler retries, then dead-letters. */
public class PaymentEventListener {

    private final HandlePaymentEventUsecase usecase;

    public PaymentEventListener(HandlePaymentEventUsecase usecase) {
        this.usecase = usecase;
    }

    @KafkaListener(topics = "${order.events.payment-topic:domain.payment.events}", groupId = "${spring.kafka.consumer.group-id:order-service}")
    public void onPaymentEvent(String payload) {
        usecase.execute(new HandlePaymentEventUsecase.Request(payload));
    }
}
