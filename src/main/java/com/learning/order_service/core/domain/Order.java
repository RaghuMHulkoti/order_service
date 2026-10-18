package com.learning.order_service.core.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The order aggregate. {@code items} is empty for list views, which do not load lines. */
public record Order(
        UUID id,
        String orderNumber,
        UUID customerId,
        OrderStatus status,
        String currency,
        BigDecimal subtotal,
        BigDecimal discount,
        BigDecimal shippingFee,
        BigDecimal tax,
        BigDecimal total,
        Address shippingAddress,
        Address billingAddress,
        UUID paymentIntentId,
        String idempotencyKey,
        Instant placedAt,
        Instant confirmedAt,
        Instant cancelledAt,
        String cancelReason,
        List<OrderItem> items
) {

    public Order withItems(List<OrderItem> newItems) {
        return new Order(id, orderNumber, customerId, status, currency, subtotal, discount, shippingFee, tax, total,
                shippingAddress, billingAddress, paymentIntentId, idempotencyKey, placedAt, confirmedAt, cancelledAt,
                cancelReason, newItems);
    }
}
