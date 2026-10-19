package com.learning.order_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code paymentTimeout} is how long a customer has to pay; {@code reservationTtl} is how long Inventory holds the
 * stock and must be longer, so a payment captured just before the timeout can still be deducted.
 * {@code maxPaymentAttempts} is how many payment intents an order may use before a failed payment fails the order.
 */
@ConfigurationProperties(prefix = "order")
public record OrderProperties(Duration paymentTimeout, Duration reservationTtl,
                              @org.springframework.boot.context.properties.bind.DefaultValue("3") int maxPaymentAttempts) {
}
