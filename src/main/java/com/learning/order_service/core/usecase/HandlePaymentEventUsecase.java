package com.learning.order_service.core.usecase;

import com.learning.order_service.core.domain.Order;
import com.learning.order_service.core.domain.OrderStatus;
import com.learning.order_service.core.service.lifecycle.OrderLifecycleService;
import com.learning.order_service.core.service.lifecycle.OrderLifecycleServiceRequest;
import com.learning.order_service.gateway.OrderGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;

/**
 * Reacts to Payment Service's events (TDD §12.6). PAYMENT_INITIATED records the intent id on the order. PAYMENT_SUCCESS
 * / PAYMENT_FAILED settle the order right away instead of waiting for the settlement job. The event only says which order to look at; SETTLE reads the payment itself, so a
 * stale or replayed event can never confirm or fail an order wrongly, and SETTLE is safe to repeat.
 * The event id is recorded after the order is settled, so a failure mid-way is redelivered and tried again.
 */
public class HandlePaymentEventUsecase
        implements Usecase<HandlePaymentEventUsecase.Request, HandlePaymentEventUsecase.Response> {

    static final String CONSUMER = "order-service.payment-events";
    private static final Set<String> HANDLED = Set.of("PAYMENT_INITIATED", "PAYMENT_SUCCESS", "PAYMENT_FAILED");
    private static final Logger log = LoggerFactory.getLogger(HandlePaymentEventUsecase.class);

    /** {@code payload} is the event envelope JSON exactly as Payment Service published it. */
    public record Request(String payload) implements UsecaseRequest {
    }

    public record Response(Outcome outcome) implements UsecaseResponse {
    }

    public enum Outcome { IGNORED, DUPLICATE, ORDER_UNKNOWN, SETTLED }

    private final OrderGateway orders;
    private final OrderLifecycleService lifecycle;
    private final JsonMapper json;
    private final Clock clock;

    public HandlePaymentEventUsecase(OrderGateway orders, OrderLifecycleService lifecycle, JsonMapper json, Clock clock) {
        this.orders = orders;
        this.lifecycle = lifecycle;
        this.json = json;
        this.clock = clock;
    }

    /** @throws IllegalArgumentException when a PAYMENT_* event is malformed: retried, then sent to the dead-letter topic */
    @Override
    public Response execute(Request request) {
        var root = json.readTree(request.payload());
        if (!HANDLED.contains(root.path("eventType").asString(""))) {
            return new Response(Outcome.IGNORED);
        }
        var eventId = UUID.fromString(root.path("eventId").asString(""));
        var orderId = UUID.fromString(root.path("data").path("orderId").asString(""));
        if (orders.isEventProcessed(eventId)) {
            return new Response(Outcome.DUPLICATE);
        }
        Order order = orders.find(orderId).orElse(null);
        if (order == null) {
            log.warn("Payment event {} is for unknown order {}; ignored", eventId, orderId);
            return new Response(Outcome.ORDER_UNKNOWN);
        }
        var data = root.path("data");
        var type = root.path("eventType").asString("");
        if (type.equals("PAYMENT_INITIATED")) {
            orders.setPaymentIntent(orderId, UUID.fromString(data.path("paymentIntentId").asString("")));
        } else if (type.equals("PAYMENT_FAILED") && order.paymentIntentId() == null) {
            // Payment could not even open the intent: there is nothing to read back, so the event itself ends the order
            if (order.status().isOpen()) {
                lifecycle.execute(OrderLifecycleServiceRequest.builder()
                        .operation(OrderLifecycleServiceRequest.Operation.ABORT).order(order).target(OrderStatus.FAILED)
                        .reason(data.path("failureCode").asString("PAYMENT_INIT_FAILED")).actor("payment").build());
            }
        } else {
            lifecycle.execute(OrderLifecycleServiceRequest.builder()
                    .operation(OrderLifecycleServiceRequest.Operation.SETTLE).order(order).build());
        }
        orders.markEventProcessed(eventId, CONSUMER, clock.instant());
        return new Response(Outcome.SETTLED);
    }
}
