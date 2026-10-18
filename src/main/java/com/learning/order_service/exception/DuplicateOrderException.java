package com.learning.order_service.exception;

/** Another request already placed an order with the same (customer, idempotency key). Caught by the place use case. */
public class DuplicateOrderException extends RuntimeException {

    public DuplicateOrderException() {
        super("An order with this idempotency key already exists");
    }
}
