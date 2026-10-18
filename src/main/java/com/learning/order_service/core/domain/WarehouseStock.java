package com.learning.order_service.core.domain;

import java.util.UUID;

public record WarehouseStock(UUID warehouseId, int available) {
}
