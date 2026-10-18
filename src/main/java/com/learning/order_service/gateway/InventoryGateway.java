package com.learning.order_service.gateway;

import com.learning.order_service.core.domain.Reservation;
import com.learning.order_service.core.domain.WarehouseStock;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

public interface InventoryGateway {

    record ReserveLine(UUID orderItemId, UUID supplierProductId, UUID warehouseId, int quantity) {
    }

    /** Available units per warehouse for one offer; empty when Inventory has no stock record for it. */
    List<WarehouseStock> stockFor(UUID supplierProductId);

    /**
     * All-or-nothing, and idempotent per order: replaying the same order returns the same reservations.
     * Throws BusinessRuleViolationException(INSUFFICIENT_STOCK) when any line cannot be held.
     */
    List<Reservation> reserve(UUID orderId, List<ReserveLine> lines, Duration ttl);

    /** Idempotent: releasing a reservation that is no longer held succeeds. */
    void release(UUID reservationId, String reason);

    /** Turns held stock into a real deduction. Idempotent: reservations already committed are skipped. */
    void deduct(UUID orderId, List<Reservation> reservations);
}
