package com.learning.order_service.core.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** The customer's cart as Cart Service renders it: live prices and per-line availability. */
public record CartSnapshot(List<Line> lines, String currency, boolean checkoutReady) {

    /** {@code offerId} is the supplier_product id (the cart's itemId). */
    public record Line(UUID offerId, UUID productId, UUID supplierId, String title, int quantity, BigDecimal unitPrice) {
    }
}
