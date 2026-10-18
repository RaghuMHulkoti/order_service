package com.learning.order_service.core.domain;

import java.util.UUID;

/** What Payment Service reports about an intent. {@code clientSecret} is only present right after creation. */
public record PaymentSnapshot(UUID paymentId, String status, String clientSecret, String failureCode,
                              String failureMessage) {

    /** Money was taken at some point, so the order is paid for. */
    public boolean isPaid() {
        return "CAPTURED".equals(status) || "PARTIALLY_REFUNDED".equals(status) || "REFUNDED".equals(status);
    }

    /** The payment can no longer succeed. */
    public boolean isDead() {
        return "FAILED".equals(status) || "CANCELLED".equals(status);
    }
}
