package com.learning.order_service.core.usecase;

import com.learning.order_service.config.OrderProperties;
import com.learning.order_service.core.domain.Address;
import com.learning.order_service.core.domain.CartSnapshot;
import com.learning.order_service.core.domain.Order;
import com.learning.order_service.core.domain.OrderItem;
import com.learning.order_service.core.domain.OrderStatus;
import com.learning.order_service.core.domain.WarehouseStock;
import com.learning.order_service.core.service.lifecycle.OrderLifecycleService;
import com.learning.order_service.core.service.lifecycle.OrderLifecycleServiceRequest;
import com.learning.order_service.exception.BusinessRuleViolationException;
import com.learning.order_service.exception.DependencyUnavailableException;
import com.learning.order_service.exception.DuplicateOrderException;
import com.learning.order_service.gateway.CartGateway;
import com.learning.order_service.gateway.InventoryGateway;
import com.learning.order_service.gateway.InventoryGateway.ReserveLine;
import com.learning.order_service.gateway.OrderGateway;
import com.learning.order_service.gateway.OrderGateway.Transition;
import com.learning.order_service.gateway.PaymentGateway;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Starts the checkout saga (TDD §12.6): snapshot the cart into an order, hold the stock, open a payment intent.
 * The customer then pays with the returned client secret (or {@link PayOrderUsecase}); settlement finishes the saga.
 * Each step is recorded on the order, so a failure at any step is compensated and the order ends FAILED. Replaying
 * a request with the same idempotency key returns the first order instead of creating another.
 */
public class PlaceOrderUsecase implements Usecase<PlaceOrderUsecase.Request, PlaceOrderUsecase.Response> {

    public record Request(UUID customerId, String idempotencyKey, Address shippingAddress, Address billingAddress,
                          String provider) implements UsecaseRequest {
    }

    /** {@code clientSecret} is null unless the order is waiting for payment. */
    public record Response(Order order, String clientSecret, boolean replayed) implements UsecaseResponse {
    }

    private final OrderGateway orders;
    private final CartGateway carts;
    private final InventoryGateway inventory;
    private final PaymentGateway payments;
    private final OrderLifecycleService lifecycle;
    private final OrderProperties properties;
    private final Clock clock;

    public PlaceOrderUsecase(OrderGateway orders, CartGateway carts, InventoryGateway inventory, PaymentGateway payments,
                             OrderLifecycleService lifecycle, OrderProperties properties, Clock clock) {
        this.orders = orders;
        this.carts = carts;
        this.inventory = inventory;
        this.payments = payments;
        this.lifecycle = lifecycle;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Response execute(Request request) {
        var existing = orders.findByIdempotencyKey(request.customerId(), request.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), request);
        }

        var order = buildOrder(request);
        try {
            orders.insert(order, clock.instant());
        } catch (DuplicateOrderException lostRace) {
            return replay(orders.findByIdempotencyKey(request.customerId(), request.idempotencyKey()).orElseThrow(), request);
        }

        try {
            var reservations = inventory.reserve(order.id(), order.items().stream()
                    .map(i -> new ReserveLine(i.id(), i.supplierProductId(), i.warehouseId(), i.quantity())).toList(),
                    properties.reservationTtl());
            orders.saveReservations(order.id(), reservations);
            orders.transition(new Transition(order.id(), OrderStatus.CREATED, OrderStatus.PAYMENT_PENDING, null,
                    "customer", "PAY", "RUNNING", null, null, null, clock.instant()));
        } catch (RuntimeException e) {
            compensate(order.id(), reasonFor(e, "RESERVATION_FAILED"), e);
            throw e;
        }

        try {
            var payment = payments.createIntent(order.id(), order.customerId(), order.total(), order.currency(),
                    request.provider());
            orders.setPaymentIntent(order.id(), payment.paymentId());
            return new Response(orders.find(order.id()).orElseThrow(), payment.clientSecret(), false);
        } catch (RuntimeException e) {
            compensate(order.id(), reasonFor(e, "PAYMENT_INIT_FAILED"), e);
            throw e;
        }
    }

    /** Re-asking for an order that is waiting for payment re-reads the (idempotent) intent to hand back its secret. */
    private Response replay(Order order, Request request) {
        if (order.status() != OrderStatus.PAYMENT_PENDING) {
            return new Response(order, null, true);
        }
        var payment = payments.createIntent(order.id(), order.customerId(), order.total(), order.currency(),
                request.provider());
        if (order.paymentIntentId() == null) {
            orders.setPaymentIntent(order.id(), payment.paymentId());
            order = orders.find(order.id()).orElse(order);
        }
        return new Response(order, payment.clientSecret(), true);
    }

    private Order buildOrder(Request request) {
        var cart = carts.fetch(request.customerId());
        if (cart.lines().isEmpty()) {
            throw new BusinessRuleViolationException("CART_EMPTY", "The cart is empty");
        }
        if (!cart.checkoutReady()) {
            throw new BusinessRuleViolationException("CART_NOT_READY",
                    "The cart has items that are unavailable, out of stock or could not be verified");
        }
        var items = cart.lines().stream().map(this::toItem).toList();
        var subtotal = items.stream().map(OrderItem::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        var now = clock.instant();
        // Discounts, shipping and tax are not modelled yet, so the total is the subtotal.
        return new Order(UUID.randomUUID(), orders.nextOrderNumber(now), request.customerId(), OrderStatus.CREATED,
                cart.currency(), subtotal, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, subtotal,
                request.shippingAddress(), request.billingAddress(), null, request.idempotencyKey(), now, null, null, null,
                items);
    }

    private OrderItem toItem(CartSnapshot.Line line) {
        if (line.title() == null) {
            // The order keeps a title snapshot for its invoice, so refuse rather than store a blank one.
            throw new DependencyUnavailableException("Product details are unavailable. Retry shortly.", null);
        }
        var warehouse = inventory.stockFor(line.offerId()).stream()
                .filter(s -> s.available() >= line.quantity())
                .max(Comparator.comparingInt(WarehouseStock::available))
                .orElseThrow(() -> new BusinessRuleViolationException("INSUFFICIENT_STOCK",
                        "Not enough stock for " + line.title()));
        return new OrderItem(UUID.randomUUID(), line.productId(), line.offerId(), line.supplierId(), line.title(),
                line.unitPrice(), line.quantity(), line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())),
                "PENDING", warehouse.warehouseId(), null);
    }

    /** Undo what the saga did so far. A failure here must not hide the original error; the settlement job retries. */
    private void compensate(UUID orderId, String reason, RuntimeException original) {
        try {
            var order = orders.find(orderId).orElseThrow();
            lifecycle.execute(OrderLifecycleServiceRequest.builder()
                    .operation(OrderLifecycleServiceRequest.Operation.ABORT)
                    .order(order).target(OrderStatus.FAILED).reason(reason).actor("system").build());
        } catch (RuntimeException e) {
            original.addSuppressed(e);
        }
    }

    private static String reasonFor(RuntimeException e, String fallback) {
        return e instanceof BusinessRuleViolationException b ? b.getErrorCode() : fallback;
    }
}
