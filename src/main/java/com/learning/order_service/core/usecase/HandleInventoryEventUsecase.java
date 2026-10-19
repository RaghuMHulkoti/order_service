package com.learning.order_service.core.usecase;

import com.learning.order_service.core.domain.Order;
import com.learning.order_service.core.domain.OrderEvents;
import com.learning.order_service.core.domain.OrderStatus;
import com.learning.order_service.core.domain.Reservation;
import com.learning.order_service.core.service.lifecycle.OrderLifecycleService;
import com.learning.order_service.core.service.lifecycle.OrderLifecycleServiceRequest;
import com.learning.order_service.gateway.InventoryGateway;
import com.learning.order_service.gateway.OrderGateway;
import com.learning.order_service.gateway.OrderGateway.Transition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Inventory's answer to ORDER_CREATED (TDD §12.6). INVENTORY_RESERVED records the held stock, moves the order to
 * PAYMENT_PENDING and asks Payment for an intent (PAYMENT_REQUESTED, written to the outbox in the same transaction
 * as the status change). INVENTORY_RESERVATION_FAILED fails the order; nothing is held, so nothing is released.
 * A reservation that arrives after the order already ended (timed out, cancelled) is released again, so late
 * stock never stays held. The event id is recorded last, so a failure midway is redelivered and tried again.
 */
public class HandleInventoryEventUsecase
        implements Usecase<HandleInventoryEventUsecase.Request, HandleInventoryEventUsecase.Response> {

    static final String CONSUMER = "order-service.inventory-events";
    private static final Set<String> HANDLED = Set.of("INVENTORY_RESERVED", "INVENTORY_RESERVATION_FAILED");
    private static final Logger log = LoggerFactory.getLogger(HandleInventoryEventUsecase.class);

    /** {@code payload} is the event envelope JSON exactly as Inventory published it. */
    public record Request(String payload) implements UsecaseRequest {
    }

    public record Response(Outcome outcome) implements UsecaseResponse {
    }

    public enum Outcome { IGNORED, DUPLICATE, ORDER_UNKNOWN, RESERVED, FAILED, LATE_RELEASED }

    private final OrderGateway orders;
    private final InventoryGateway inventory;
    private final OrderLifecycleService lifecycle;
    private final JsonMapper json;
    private final Clock clock;

    public HandleInventoryEventUsecase(OrderGateway orders, InventoryGateway inventory, OrderLifecycleService lifecycle,
                                       JsonMapper json, Clock clock) {
        this.orders = orders;
        this.inventory = inventory;
        this.lifecycle = lifecycle;
        this.json = json;
        this.clock = clock;
    }

    /** @throws IllegalArgumentException when an INVENTORY_* event is malformed: retried, then sent to the dead-letter topic */
    @Override
    public Response execute(Request request) {
        var root = json.readTree(request.payload());
        var type = root.path("eventType").asString("");
        if (!HANDLED.contains(type)) {
            return new Response(Outcome.IGNORED);
        }
        var eventId = UUID.fromString(root.path("eventId").asString(""));
        var data = root.path("data");
        var orderId = UUID.fromString(data.path("orderId").asString(""));
        if (orders.isEventProcessed(eventId)) {
            return new Response(Outcome.DUPLICATE);
        }
        var order = orders.find(orderId).orElse(null);
        if (order == null) {
            log.warn("Inventory event {} is for unknown order {}; ignored", eventId, orderId);
            return new Response(Outcome.ORDER_UNKNOWN);
        }

        Outcome outcome;
        if (type.equals("INVENTORY_RESERVATION_FAILED")) {
            var reason = data.path("reason").asString("RESERVATION_FAILED");
            if (order.status().isOpen()) {
                lifecycle.execute(OrderLifecycleServiceRequest.builder()
                        .operation(OrderLifecycleServiceRequest.Operation.ABORT).order(order)
                        .target(OrderStatus.FAILED).reason(reason).actor("inventory").build());
            }
            outcome = Outcome.FAILED;
        } else {
            var reservations = new ArrayList<Reservation>();
            data.path("reservations").forEach(r -> reservations.add(new Reservation(
                    UUID.fromString(r.path("orderItemId").asString("")),
                    UUID.fromString(r.path("reservationId").asString("")))));
            outcome = order.status() == OrderStatus.CREATED ? reserved(order.id(), order, reservations)
                    : releaseLate(order, reservations);
        }
        orders.markEventProcessed(eventId, CONSUMER, clock.instant());
        return new Response(outcome);
    }

    private Outcome reserved(UUID orderId, Order order, List<Reservation> reservations) {
        orders.saveReservations(orderId, reservations);
        var now = clock.instant();
        var data = new LinkedHashMap<String, Object>();
        data.put("orderId", orderId.toString());
        data.put("customerId", order.customerId().toString());
        data.put("amount", order.total());
        data.put("currency", order.currency());
        data.put("provider", order.paymentProvider());
        boolean moved = orders.transition(new Transition(orderId, OrderStatus.CREATED, OrderStatus.PAYMENT_PENDING, null,
                "inventory", "PAY", "RUNNING", null, "PAYMENT_REQUESTED",
                OrderEvents.envelope(json, "PAYMENT_REQUESTED", now, data), now));
        if (!moved) {
            // another actor ended the order first; the stock this event reserved must not stay held
            return releaseLate(order, reservations);
        }
        return Outcome.RESERVED;
    }

    /**
     * Releases stock the order does not own. Inventory re-emits INVENTORY_RESERVED with the same reservation ids when
     * ORDER_CREATED is redelivered, so reservations already recorded on the order are kept.
     */
    private Outcome releaseLate(Order order, List<Reservation> reservations) {
        var owned = order.items().stream().map(i -> i.reservationId()).filter(java.util.Objects::nonNull).toList();
        reservations.stream().filter(r -> !owned.contains(r.reservationId()))
                .forEach(r -> inventory.release(r.reservationId(), "ORDER_NOT_PENDING"));
        return Outcome.LATE_RELEASED;
    }
}
