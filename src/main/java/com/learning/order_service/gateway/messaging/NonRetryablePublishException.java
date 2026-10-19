package com.learning.order_service.gateway.messaging;

/** The broker rejected the event for good (e.g. too large). Retrying will not help. */
public class NonRetryablePublishException extends RuntimeException {

    public NonRetryablePublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
