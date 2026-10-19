package com.learning.order_service.core.service.lifecycle;

import com.learning.order_service.config.OrderProperties;
import com.learning.order_service.core.domain.Order;
import com.learning.order_service.core.domain.OrderEvents;
import com.learning.order_service.core.domain.OrderStatus;
import com.learning.order_service.core.domain.PaymentSnapshot;
import com.learning.order_service.core.domain.Reservation;
import com.learning.order_service.core.service.Service;
import com.learning.order_service.exception.BusinessRuleViolationException;
import com.learning.order_service.gateway.CartGateway;
import com.learning.order_service.gateway.InventoryGateway;
import com.learning.order_service.gateway.OrderGateway;
import com.learning.order_service.gateway.OrderGateway.Transition;
import com.learning.order_service.gateway.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The two ways a checkout saga ends (TDD §12.6, §12.12):
 * <ul>
 *   <li>SETTLE reads the payment. Paid: deduct the held stock and confirm. Dead: compensate. Still open: do nothing.</li>
 *   <li>ABORT compensates: release the held stock, then move the order to CANCELLED or FAILED.</li>
 * </ul>
 * Both are safe to repeat, so the customer's request, the settlement job and a retry can all call them.
 */
public class OrderLifecycleService implements Service<OrderLifecycleServiceRequest, OrderLifecycleServiceResponse> {

    private static final Logger log = LoggerFactory.getLogger(OrderLifecycleService.class);

    private final OrderGateway orders;
    private final InventoryGateway inventory;
    private final PaymentGateway payments;
    private final CartGateway carts;
    private final JsonMapper json;
    private final Clock clock;
    private final OrderProperties properties;

    public OrderLifecycleService(OrderGateway orders, InventoryGateway inventory, PaymentGateway payments,
                                 CartGateway carts, JsonMapper json, Clock clock, OrderProperties properties) {
        this.orders = orders;
        this.inventory = inventory;
        this.payments = payments;
        this.carts = carts;
        this.json = json;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public OrderLifecycleServiceResponse execute(OrderLifecycleServiceRequest request) {
        var order = switch (request.getOperation()) {
            case SETTLE -> settle(request.getOrder(), request.getPayment());
            case ABORT -> abort(request.getOrder(), request.getTarget(), request.getReason(), request.getActor());
        };
        return OrderLifecycleServiceResponse.builder().order(order).build();
    }

    private Order settle(Order order, PaymentSnapshot known) {
        if (order.status() != OrderStatus.PAYMENT_PENDING || order.paymentIntentId() == null) {
            return order;
        }
        var payment = known != null ? known : payments.get(order.paymentIntentId(), order.customerId());
        if (payment.isPaid()) {
            return confirm(order);
        }
        if (payment.isDead()) {
            if (canRetry(order, payment)) {
                return order; // the customer may pay again with another method, until attempts or time run out
            }
            return abort(order, OrderStatus.FAILED, payment.failureCode() != null ? payment.failureCode() : "PAYMENT_FAILED",
                    "payment");
        }
        return order;
    }

    /** A declined or failed payment can be retried; a cancelled one cannot, and attempts are limited. */
    public boolean canRetry(Order order, PaymentSnapshot payment) {
        return payment.isDead() && !"CANCELLED".equals(payment.status())
                && order.paymentAttempts() < properties.maxPaymentAttempts();
    }

    private Order confirm(Order order) {
        try {
            inventory.deduct(order.id(), order.items().stream()
                    .map(i -> new Reservation(i.id(), i.reservationId())).toList());
        } catch (BusinessRuleViolationException e) {
            // The money is taken but the stock can no longer be committed: give the money back.
            log.error("Order {} is paid but stock could not be deducted ({}); refunding", order.id(), e.getErrorCode());
            payments.refund(order.id(), "FULFILMENT_FAILED");
            return abort(order, OrderStatus.FAILED, "FULFILMENT_FAILED", "system");
        }
        var now = clock.instant();
        boolean moved = orders.transition(new Transition(order.id(), OrderStatus.PAYMENT_PENDING, OrderStatus.CONFIRMED,
                null, "payment", "DONE", "COMPLETED", "PENDING", "ORDER_CONFIRMED",
                event(order, "ORDER_CONFIRMED", OrderStatus.CONFIRMED, null), now));
        if (moved) {
            clearOrderedLines(order);
        }
        return reload(order);
    }

    private Order abort(Order order, OrderStatus target, String reason, String actor) {
        if (!order.status().isOpen()) {
            return order;
        }
        for (var item : order.items()) {
            if (item.reservationId() != null) {
                inventory.release(item.reservationId(), reason);
            }
        }
        var type = target == OrderStatus.CANCELLED ? "ORDER_CANCELLED" : "ORDER_FAILED";
        orders.transition(new Transition(order.id(), order.status(), target, reason, actor, "COMPENSATED", "FAILED",
                "CANCELLED", type, event(order, type, target, reason), clock.instant()));
        return reload(order);
    }

    /** Only the lines that were ordered: anything the customer added to the cart since stays. */
    private void clearOrderedLines(Order order) {
        for (var item : order.items()) {
            try {
                carts.removeLine(order.customerId(), item.supplierProductId());
            } catch (RuntimeException e) {
                log.warn("Order {} confirmed but cart line {} was not removed: {}", order.id(), item.supplierProductId(),
                        e.getMessage());
            }
        }
    }

    private Order reload(Order order) {
        return orders.find(order.id()).orElse(order);
    }

    /** Envelope follows TDD §13.3, same as Payment's outbox. */
    private String event(Order order, String type, OrderStatus status, String reason) {
        var data = new LinkedHashMap<String, Object>();
        data.put("orderId", order.id().toString());
        data.put("orderNumber", order.orderNumber());
        data.put("customerId", order.customerId().toString());
        data.put("status", status.name());
        data.put("total", order.total());
        data.put("currency", order.currency());
        if (reason != null) {
            data.put("reason", reason);
        }
        if ("ORDER_CONFIRMED".equals(type)) {
            // the confirmation email lists what was bought
            data.put("items", order.items().stream().map(i -> Map.of(
                    "title", i.title(), "quantity", i.quantity(), "unitPrice", i.unitPrice())).toList());
        }
        return OrderEvents.envelope(json, type, clock.instant(), data);
    }
}
