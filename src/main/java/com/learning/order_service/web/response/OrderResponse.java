package com.learning.order_service.web.response;

import com.learning.order_service.core.domain.Address;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code items} is empty in list views. {@code nextAction} is only set on a freshly placed order awaiting payment. */
public record OrderResponse(
        UUID orderId,
        String orderNumber,
        String status,
        String currency,
        Amount amount,
        Address shippingAddress,
        Address billingAddress,
        UUID paymentId,
        List<Item> items,
        Instant placedAt,
        Instant confirmedAt,
        Instant cancelledAt,
        String cancelReason,
        NextAction nextAction
) {

    public record Amount(BigDecimal subtotal, BigDecimal discount, BigDecimal tax, BigDecimal shipping, BigDecimal total) {
    }

    public record Item(UUID itemId, UUID productId, UUID supplierProductId, UUID supplierId, String title,
                       BigDecimal unitPrice, int quantity, BigDecimal lineTotal, String fulfilmentStatus) {
    }

    /** The client confirms the payment with the PSP SDK using {@code clientSecret}. */
    public record NextAction(String kind, String clientSecret) {
    }
}
