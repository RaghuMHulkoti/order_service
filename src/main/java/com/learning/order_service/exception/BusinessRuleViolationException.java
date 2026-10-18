package com.learning.order_service.exception;

/** A well-formed request that breaks an order rule (stock, cart not ready, wrong order state). Maps to 422. */
public class BusinessRuleViolationException extends RuntimeException {

    private final String errorCode;

    public BusinessRuleViolationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
