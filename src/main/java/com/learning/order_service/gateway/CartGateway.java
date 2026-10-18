package com.learning.order_service.gateway;

import com.learning.order_service.core.domain.CartSnapshot;

import java.util.UUID;

public interface CartGateway {

    /** The customer's cart with live prices and availability. */
    CartSnapshot fetch(UUID customerId);

    /** Removes one line; removing a line that is already gone succeeds. */
    void removeLine(UUID customerId, UUID offerId);
}
