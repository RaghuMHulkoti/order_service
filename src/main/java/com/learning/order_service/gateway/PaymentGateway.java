package com.learning.order_service.gateway;

import com.learning.order_service.core.domain.PaymentSnapshot;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentGateway {

    /**
     * Idempotent per order and attempt: asking again returns the same intent, with its client secret while still open.
     * Attempt 1 is the intent opened by the saga; later attempts are retries after a failed payment.
     */
    PaymentSnapshot createIntent(UUID orderId, UUID customerId, BigDecimal amount, String currency, String provider,
                                 int attempt);

    /** Server-side confirm with a payment-method token. A decline is returned as a FAILED intent, not thrown. */
    PaymentSnapshot confirm(UUID paymentId, String paymentMethodToken);

    PaymentSnapshot get(UUID paymentId, UUID customerId);

    /** Full refund of the order's captured payment. Idempotent per order. */
    void refund(UUID orderId, String reason);
}
