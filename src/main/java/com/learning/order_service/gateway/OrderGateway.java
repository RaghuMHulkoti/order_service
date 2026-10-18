package com.learning.order_service.gateway;

import com.learning.order_service.core.domain.Order;
import com.learning.order_service.core.domain.OrderStatus;
import com.learning.order_service.core.domain.Reservation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Order store. Implementations throw DuplicateOrderException when the (customer, idempotency key) pair already
 * exists and DependencyUnavailableException when the store is down.
 */
public interface OrderGateway {

    /** Everything one status change writes, atomically: the order row, history, saga step, item status and outbox event. */
    record Transition(UUID orderId, OrderStatus from, OrderStatus to, String reason, String actor, String sagaStep,
                      String sagaState, String itemStatus, String eventType, String eventPayload, Instant now) {
    }

    /** Next human-readable number, e.g. BZ-26101800042 (day + database sequence). */
    String nextOrderNumber(Instant now);

    /** Inserts the order, its lines, the first history row and the saga row in one transaction. */
    void insert(Order order, Instant now);

    Optional<Order> find(UUID orderId);

    Optional<Order> findByIdempotencyKey(UUID customerId, String idempotencyKey);

    /** Newest first, lines not loaded. A cursor is the (placedAt, id) of the last order of the previous page. */
    List<Order> list(UUID customerId, OrderStatus status, Instant beforePlacedAt, UUID beforeId, int limit);

    /** Orders still in the checkout saga, oldest first, lines loaded. */
    List<Order> findOpen(int limit);

    void saveReservations(UUID orderId, List<Reservation> reservations);

    void setPaymentIntent(UUID orderId, UUID paymentIntentId);

    /** False when the order is no longer in {@code from}: another request moved it first, nothing was written. */
    boolean transition(Transition transition);
}
