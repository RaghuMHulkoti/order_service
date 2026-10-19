package com.learning.order_service.core.usecase;

import com.learning.order_service.config.OrderProperties;
import com.learning.order_service.core.domain.Address;
import com.learning.order_service.core.domain.CartSnapshot;
import com.learning.order_service.core.domain.Order;
import com.learning.order_service.core.domain.OrderItem;
import com.learning.order_service.core.domain.OrderStatus;
import com.learning.order_service.core.domain.WarehouseStock;
import com.learning.order_service.exception.BusinessRuleViolationException;
import com.learning.order_service.exception.DependencyUnavailableException;
import com.learning.order_service.exception.DuplicateOrderException;
import com.learning.order_service.gateway.CartGateway;
import com.learning.order_service.gateway.InventoryGateway;
import com.learning.order_service.gateway.OrderGateway;
import com.learning.order_service.gateway.PaymentGateway;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Starts the checkout saga (TDD §12.6): snapshot the cart into an order and publish ORDER_CREATED. The rest runs on
 * events: Inventory holds the stock ({@link HandleInventoryEventUsecase}), then Payment opens the intent
 * ({@link HandlePaymentEventUsecase}), and settlement finishes the saga. The order is returned in CREATED.
 * Replaying a request with the same idempotency key returns the first order instead of creating another; once the
 * order is waiting for payment, the replay also returns the client secret, which never travels through the bus.
 */
public class PlaceOrderUsecase implements Usecase<PlaceOrderUsecase.Request, PlaceOrderUsecase.Response> {

    public record Request(UUID customerId, String idempotencyKey, Address shippingAddress, Address billingAddress,
                          String provider) implements UsecaseRequest {
    }

    /** {@code clientSecret} is null until the order is waiting for payment and its intent exists. */
    public record Response(Order order, String clientSecret, boolean replayed) implements UsecaseResponse {
    }

    private final OrderGateway orders;
    private final CartGateway carts;
    private final InventoryGateway inventory;
    private final PaymentGateway payments;
    private final OrderProperties properties;
    private final Clock clock;

    public PlaceOrderUsecase(OrderGateway orders, CartGateway carts, InventoryGateway inventory, PaymentGateway payments,
                             OrderProperties properties, Clock clock) {
        this.orders = orders;
        this.carts = carts;
        this.inventory = inventory;
        this.payments = payments;
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
            orders.insert(order, clock.instant(), properties.reservationTtl().toSeconds());
        } catch (DuplicateOrderException lostRace) {
            return replay(orders.findByIdempotencyKey(request.customerId(), request.idempotencyKey()).orElseThrow(), request);
        }
        return new Response(order, null, false);
    }

    /**
     * Re-asking for an order that is waiting for payment re-reads the (idempotent) intent to hand back its secret.
     * Until the intent event has been applied there is nothing to read, so the caller simply asks again.
     */
    private Response replay(Order order, Request request) {
        if (order.status() != OrderStatus.PAYMENT_PENDING || order.paymentIntentId() == null) {
            return new Response(order, null, true);
        }
        var payment = payments.createIntent(order.id(), order.customerId(), order.total(), order.currency(),
                request.provider(), order.paymentAttempts());
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
                request.shippingAddress(), request.billingAddress(), null, request.provider(), 1, request.idempotencyKey(),
                now, null, null, null, items);
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
}
