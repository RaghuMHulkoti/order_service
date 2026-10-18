package com.learning.order_service.core.domain;

import java.util.UUID;

/** Stock Inventory is holding for one order line. */
public record Reservation(UUID orderItemId, UUID reservationId) {
}
