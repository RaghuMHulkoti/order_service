package com.learning.order_service.exception;

import java.util.UUID;

/** Also thrown for an order that belongs to someone else, so ids cannot be probed. */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(UUID orderId) {
        super("Order not found: " + orderId);
    }
}
