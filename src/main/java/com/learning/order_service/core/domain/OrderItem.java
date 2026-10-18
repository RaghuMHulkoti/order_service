package com.learning.order_service.core.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One purchased line. {@code supplierProductId} is the offer id, the key Inventory stock is held under;
 * title and price are snapshots of what the customer saw at checkout.
 */
public record OrderItem(
        UUID id,
        UUID productId,
        UUID supplierProductId,
        UUID supplierId,
        String title,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineTotal,
        String fulfilmentStatus,
        UUID warehouseId,
        UUID reservationId
) {
}
