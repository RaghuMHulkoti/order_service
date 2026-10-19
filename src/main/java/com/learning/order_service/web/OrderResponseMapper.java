package com.learning.order_service.web;

import com.learning.order_service.core.domain.Order;
import com.learning.order_service.web.response.OrderResponse;

final class OrderResponseMapper {

    private OrderResponseMapper() {
    }

    static OrderResponse toResponse(Order o) {
        return toResponse(o, null);
    }

    static OrderResponse toResponse(Order o, String clientSecret) {
        var items = o.items().stream()
                .map(i -> new OrderResponse.Item(i.id(), i.productId(), i.supplierProductId(), i.supplierId(), i.title(),
                        i.unitPrice(), i.quantity(), i.lineTotal(), i.fulfilmentStatus()))
                .toList();
        return new OrderResponse(o.id(), o.orderNumber(), o.status().name(), o.currency(),
                new OrderResponse.Amount(o.subtotal(), o.discount(), o.tax(), o.shippingFee(), o.total()),
                o.shippingAddress(), o.billingAddress(), o.paymentIntentId(), o.paymentAttempts(), items, o.placedAt(), o.confirmedAt(),
                o.cancelledAt(), o.cancelReason(),
                clientSecret == null ? null : new OrderResponse.NextAction("CONFIRM_PAYMENT", clientSecret));
    }
}
