package com.learning.order_service.exception;

/** The order database or a downstream service (cart, inventory, payment) did not answer. Maps to 503. */
public class DependencyUnavailableException extends RuntimeException {

    public DependencyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
