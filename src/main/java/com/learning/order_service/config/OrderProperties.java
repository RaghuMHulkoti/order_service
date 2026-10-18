package com.learning.order_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code paymentTimeout} is how long a customer has to pay; {@code reservationTtl} is how long Inventory holds the
 * stock and must be longer, so a payment captured just before the timeout can still be deducted.
 */
@ConfigurationProperties(prefix = "order")
public record OrderProperties(Duration paymentTimeout, Duration reservationTtl) {
}
