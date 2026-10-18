package com.learning.order_service.core.domain;

/**
 * Every status the database allows (TDD §9.5). This service drives CREATED, PAYMENT_PENDING, CONFIRMED, CANCELLED
 * and FAILED; the shipping and return statuses are set by fulfilment, which is not part of this service yet.
 */
public enum OrderStatus {
    CREATED, PAYMENT_PENDING, CONFIRMED, PARTIALLY_SHIPPED, SHIPPED, DELIVERED, CANCELLED, RETURNED, FAILED;

    /** Still in the checkout saga: stock may be held and payment may still arrive. */
    public boolean isOpen() {
        return this == CREATED || this == PAYMENT_PENDING;
    }
}
