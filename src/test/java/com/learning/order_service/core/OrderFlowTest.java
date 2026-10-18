package com.learning.order_service.core;

import com.learning.order_service.config.OrderProperties;
import com.learning.order_service.core.domain.Address;
import com.learning.order_service.core.domain.CartSnapshot;
import com.learning.order_service.core.domain.OrderStatus;
import com.learning.order_service.core.domain.PaymentSnapshot;
import com.learning.order_service.core.domain.Reservation;
import com.learning.order_service.core.domain.WarehouseStock;
import com.learning.order_service.core.service.lifecycle.OrderLifecycleService;
import com.learning.order_service.core.usecase.CancelOrderUsecase;
import com.learning.order_service.core.usecase.ListOrdersUsecase;
import com.learning.order_service.core.usecase.PayOrderUsecase;
import com.learning.order_service.core.usecase.PlaceOrderUsecase;
import com.learning.order_service.core.usecase.SettleOpenOrdersUsecase;
import com.learning.order_service.exception.BusinessRuleViolationException;
import com.learning.order_service.exception.DependencyUnavailableException;
import com.learning.order_service.gateway.CartGateway;
import com.learning.order_service.gateway.InventoryGateway;
import com.learning.order_service.gateway.OrderGateway;
import com.learning.order_service.gateway.PaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole checkout saga against the real JPA gateway and migration (H2 in Oracle mode) with in-memory Cart,
 * Inventory and Payment, so every status the saga can reach is exercised, including the compensations.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:orderflow;MODE=Oracle",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.hikari.connection-test-query=SELECT 1",
        "spring.datasource.hikari.connection-init-sql=SELECT 1",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/migration/V1__create_order_schema.sql",
        "order.jobs.enabled=false"
})
class OrderFlowTest {

    static final Address ADDRESS = new Address("Asha", "1 MG Road", null, "Bengaluru", "KA", "560001", "IN", null);

    final UUID customer = UUID.randomUUID();
    final UUID productA = UUID.randomUUID();
    final UUID offerA = UUID.randomUUID();
    final UUID productB = UUID.randomUUID();
    final UUID offerB = UUID.randomUUID();

    MutableClock clock = new MutableClock(Instant.parse("2026-10-18T10:00:00Z"));
    FakeCart cart = new FakeCart();
    FakeInventory inventory = new FakeInventory();
    FakePayment payment = new FakePayment();
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    OrderGateway orders;
    @Autowired
    JsonMapper json;
    PlaceOrderUsecase place;
    PayOrderUsecase pay;
    CancelOrderUsecase cancel;
    SettleOpenOrdersUsecase settle;
    ListOrdersUsecase list;

    @BeforeEach
    void setUp() {
        // the Spring context (and its H2 database) is shared by every test, so start each one empty
        for (var table : List.of("order_outbox", "saga_state", "order_status_history", "order_items", "orders")) {
            jdbc.update("DELETE FROM " + table);
        }
        var properties = new OrderProperties(Duration.ofMinutes(15), Duration.ofMinutes(30));
        var lifecycle = new OrderLifecycleService(orders, inventory, payment, cart, json, clock);
        place = new PlaceOrderUsecase(orders, cart, inventory, payment, lifecycle, properties, clock);
        pay = new PayOrderUsecase(orders, payment, lifecycle);
        cancel = new CancelOrderUsecase(orders, lifecycle);
        settle = new SettleOpenOrdersUsecase(orders, lifecycle, properties, clock);
        list = new ListOrdersUsecase(orders);

        cart.lines.add(new CartSnapshot.Line(offerA, productA, UUID.randomUUID(), "Keyboard", 2, new BigDecimal("50.00")));
        cart.lines.add(new CartSnapshot.Line(offerB, productB, UUID.randomUUID(), "Mouse", 1, new BigDecimal("20.50")));
        inventory.stock.put(offerA, 10);
        inventory.stock.put(offerB, 5);
    }

    PlaceOrderUsecase.Response placeOrder(String key) {
        return place.execute(new PlaceOrderUsecase.Request(customer, key, ADDRESS, null, null));
    }

    @Test
    void placeHoldsStockOpensPaymentAndWaits() {
        var result = placeOrder("k1");

        var order = result.order();
        assertThat(order.status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(order.orderNumber()).matches("BZ-\\d{11}");
        assertThat(order.subtotal()).isEqualByComparingTo("120.50");
        assertThat(order.total()).isEqualByComparingTo("120.50");
        assertThat(order.paymentIntentId()).isEqualTo(payment.intentId);
        assertThat(order.items()).hasSize(2).allSatisfy(i -> {
            assertThat(i.reservationId()).isNotNull();
            assertThat(i.fulfilmentStatus()).isEqualTo("RESERVED");
            assertThat(i.warehouseId()).isNotNull();
        });
        assertThat(result.clientSecret()).isEqualTo("secret");
        assertThat(result.replayed()).isFalse();
        assertThat(inventory.held).hasSize(2);
        assertThat(payment.createdFor).containsExactly(order.id());
    }

    @Test
    void capturedPaymentConfirmsDeductsAndClearsOrderedLines() {
        var order = placeOrder("k1").order();
        cart.lines.add(new CartSnapshot.Line(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Added later", 1, BigDecimal.TEN));

        var result = pay.execute(new PayOrderUsecase.Request(customer, order.id(), "tok_ok"));

        assertThat(result.order().status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(result.order().confirmedAt()).isNotNull();
        assertThat(result.order().items()).allSatisfy(i -> assertThat(i.fulfilmentStatus()).isEqualTo("PENDING"));
        assertThat(inventory.deducted).hasSize(2);
        assertThat(inventory.released).isEmpty();
        assertThat(cart.removed).containsExactlyInAnyOrder(offerA, offerB);
        assertThat(outboxTypes()).containsExactly("ORDER_CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT current_step || '/' || state FROM saga_state", String.class)).isEqualTo("DONE/COMPLETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_status_history", Integer.class)).isEqualTo(3);
    }

    @Test
    void payingAConfirmedOrderAgainChangesNothing() {
        var order = placeOrder("k1").order();
        pay.execute(new PayOrderUsecase.Request(customer, order.id(), "tok_ok"));
        int deductCalls = inventory.deductCalls;

        var again = pay.execute(new PayOrderUsecase.Request(customer, order.id(), "tok_ok"));

        assertThat(again.order().status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(inventory.deductCalls).isEqualTo(deductCalls);
    }

    @Test
    void declinedPaymentFailsOrderAndReleasesStock() {
        var order = placeOrder("k1").order();
        payment.confirmResult = new PaymentSnapshot(payment.intentId, "FAILED", null, "card_declined", "Your card was declined.");

        var result = pay.execute(new PayOrderUsecase.Request(customer, order.id(), "tok_fail"));

        assertThat(result.order().status()).isEqualTo(OrderStatus.FAILED);
        assertThat(result.order().cancelReason()).isEqualTo("card_declined");
        assertThat(result.payment().failureCode()).isEqualTo("card_declined");
        assertThat(inventory.released).hasSize(2);
        assertThat(inventory.deducted).isEmpty();
        assertThat(outboxTypes()).containsExactly("ORDER_FAILED");
        assertThat(cart.removed).isEmpty();
    }

    @Test
    void paymentNeedingCustomerActionLeavesOrderWaiting() {
        var order = placeOrder("k1").order();
        payment.confirmResult = new PaymentSnapshot(payment.intentId, "REQUIRES_ACTION", null, null, null);

        var result = pay.execute(new PayOrderUsecase.Request(customer, order.id(), "tok_3ds"));

        assertThat(result.order().status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(inventory.released).isEmpty();
    }

    @Test
    void replayWithSameKeyReturnsFirstOrderAndSecret() {
        var first = placeOrder("same");

        var second = placeOrder("same");

        assertThat(second.order().id()).isEqualTo(first.order().id());
        assertThat(second.replayed()).isTrue();
        assertThat(second.clientSecret()).isEqualTo("secret");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(1);
        assertThat(inventory.held).hasSize(2);
    }

    @Test
    void emptyOrUnreadyCartIsRefusedBeforeAnyOrderExists() {
        cart.ready = false;
        assertThatThrownBy(() -> placeOrder("k1")).isInstanceOfSatisfying(BusinessRuleViolationException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("CART_NOT_READY"));
        cart.lines.clear();
        assertThatThrownBy(() -> placeOrder("k2")).isInstanceOfSatisfying(BusinessRuleViolationException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("CART_EMPTY"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isZero();
    }

    @Test
    void noWarehouseWithEnoughStockRefusesBeforeAnyOrderExists() {
        inventory.stock.put(offerA, 1);

        assertThatThrownBy(() -> placeOrder("k1")).isInstanceOfSatisfying(BusinessRuleViolationException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("INSUFFICIENT_STOCK"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isZero();
    }

    @Test
    void reservationRaceLostMarksOrderFailedAndHoldsNothing() {
        inventory.failReserve = new BusinessRuleViolationException("INSUFFICIENT_STOCK", "Insufficient stock to fulfill reservation");

        assertThatThrownBy(() -> placeOrder("k1")).isInstanceOf(BusinessRuleViolationException.class);

        assertThat(jdbc.queryForObject("SELECT status || ':' || cancel_reason FROM orders", String.class))
                .isEqualTo("FAILED:INSUFFICIENT_STOCK");
        assertThat(payment.createdFor).isEmpty();
    }

    @Test
    void paymentIntentFailureReleasesTheHeldStock() {
        payment.failCreate = new DependencyUnavailableException("Payment Service unavailable", null);

        assertThatThrownBy(() -> placeOrder("k1")).isInstanceOf(DependencyUnavailableException.class);

        assertThat(jdbc.queryForObject("SELECT status FROM orders", String.class)).isEqualTo("FAILED");
        assertThat(inventory.released).hasSize(2);
    }

    @Test
    void cancelBeforePaymentReleasesStock() {
        var order = placeOrder("k1").order();

        var cancelled = cancel.execute(new CancelOrderUsecase.Request(customer, order.id())).order();

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.cancelReason()).isEqualTo("CUSTOMER_REQUEST");
        assertThat(cancelled.cancelledAt()).isNotNull();
        assertThat(inventory.released).hasSize(2);
        assertThat(cancel.execute(new CancelOrderUsecase.Request(customer, order.id())).order().status())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancelAfterMoneyArrivedConfirmsInsteadAndIsRefused() {
        var order = placeOrder("k1").order();
        payment.status = "CAPTURED";

        assertThatThrownBy(() -> cancel.execute(new CancelOrderUsecase.Request(customer, order.id())))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("ORDER_NOT_CANCELLABLE"));
        assertThat(orders.find(order.id()).orElseThrow().status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(inventory.released).isEmpty();
    }

    @Test
    void settlementJobConfirmsAWebhookPaidOrder() {
        var order = placeOrder("k1").order();
        payment.status = "CAPTURED";

        var result = settle.execute(new SettleOpenOrdersUsecase.Request(10));

        assertThat(result.settled()).isEqualTo(1);
        assertThat(orders.find(order.id()).orElseThrow().status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void settlementJobCancelsUnpaidOrdersAfterTheTimeout() {
        var order = placeOrder("k1").order();
        clock.advance(Duration.ofMinutes(14));
        assertThat(settle.execute(new SettleOpenOrdersUsecase.Request(10)).expired()).isZero();

        clock.advance(Duration.ofMinutes(2));
        var result = settle.execute(new SettleOpenOrdersUsecase.Request(10));

        assertThat(result.expired()).isEqualTo(1);
        var expired = orders.find(order.id()).orElseThrow();
        assertThat(expired.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(expired.cancelReason()).isEqualTo("PAYMENT_TIMEOUT");
        assertThat(inventory.released).hasSize(2);
    }

    @Test
    void paidButStockLostRefundsAndFailsTheOrder() {
        var order = placeOrder("k1").order();
        inventory.failDeduct = new BusinessRuleViolationException("RESERVATION_NOT_FOUND", "gone");

        var result = pay.execute(new PayOrderUsecase.Request(customer, order.id(), "tok_ok"));

        assertThat(result.order().status()).isEqualTo(OrderStatus.FAILED);
        assertThat(result.order().cancelReason()).isEqualTo("FULFILMENT_FAILED");
        assertThat(payment.refunded).containsExactly(order.id());
    }

    @Test
    void inventoryOutageDuringDeductLeavesTheOrderPendingForARetry() {
        var order = placeOrder("k1").order();
        inventory.failDeduct = new DependencyUnavailableException("Inventory Service unavailable", null);

        assertThatThrownBy(() -> pay.execute(new PayOrderUsecase.Request(customer, order.id(), "tok_ok")))
                .isInstanceOf(DependencyUnavailableException.class);
        assertThat(orders.find(order.id()).orElseThrow().status()).isEqualTo(OrderStatus.PAYMENT_PENDING);

        inventory.failDeduct = null;
        payment.status = "CAPTURED";
        settle.execute(new SettleOpenOrdersUsecase.Request(10));
        assertThat(orders.find(order.id()).orElseThrow().status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void anotherCustomerCannotSeeOrPayOrCancelTheOrder() {
        var order = placeOrder("k1").order();
        var stranger = UUID.randomUUID();

        assertThatThrownBy(() -> pay.execute(new PayOrderUsecase.Request(stranger, order.id(), "tok_ok")))
                .isInstanceOf(com.learning.order_service.exception.OrderNotFoundException.class);
        assertThatThrownBy(() -> cancel.execute(new CancelOrderUsecase.Request(stranger, order.id())))
                .isInstanceOf(com.learning.order_service.exception.OrderNotFoundException.class);
    }

    @Test
    void historyPagesNewestFirstWithoutGapsOrRepeats() {
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofSeconds(1));
            ids.add(placeOrder("k" + i).order().id());
        }

        var seen = new ArrayList<UUID>();
        String cursor = null;
        do {
            var page = list.execute(new ListOrdersUsecase.Request(customer, null, cursor, 2));
            page.orders().forEach(o -> seen.add(o.id()));
            cursor = page.nextCursor();
        } while (cursor != null);

        assertThat(seen).containsExactlyElementsOf(ids.reversed());
        assertThat(list.execute(new ListOrdersUsecase.Request(customer, OrderStatus.CONFIRMED, null, 10)).orders()).isEmpty();
        assertThatThrownBy(() -> list.execute(new ListOrdersUsecase.Request(customer, null, "garbage", 10)))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    List<String> outboxTypes() {
        return jdbc.queryForList("SELECT event_type FROM order_outbox ORDER BY id", String.class);
    }

    // ---------------------------------------------------------------- fakes

    static class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static class FakeCart implements CartGateway {
        final List<CartSnapshot.Line> lines = new ArrayList<>();
        final Set<UUID> removed = new HashSet<>();
        boolean ready = true;

        @Override
        public CartSnapshot fetch(UUID customerId) {
            return new CartSnapshot(List.copyOf(lines), "INR", ready);
        }

        @Override
        public void removeLine(UUID customerId, UUID offerId) {
            removed.add(offerId);
        }
    }

    static class FakeInventory implements InventoryGateway {
        final Map<UUID, Integer> stock = new HashMap<>();
        final Map<UUID, Reservation> held = new HashMap<>();
        final List<UUID> released = new ArrayList<>();
        final List<UUID> deducted = new ArrayList<>();
        int deductCalls;
        RuntimeException failReserve;
        RuntimeException failDeduct;

        @Override
        public List<WarehouseStock> stockFor(UUID supplierProductId) {
            return List.of(new WarehouseStock(UUID.randomUUID(), stock.getOrDefault(supplierProductId, 0)));
        }

        @Override
        public List<Reservation> reserve(UUID orderId, List<ReserveLine> lines, Duration ttl) {
            if (failReserve != null) {
                throw failReserve;
            }
            assertThat(ttl).isEqualTo(Duration.ofMinutes(30));
            var result = new ArrayList<Reservation>();
            for (var line : lines) {
                result.add(held.computeIfAbsent(line.orderItemId(), id -> new Reservation(id, UUID.randomUUID())));
            }
            return result;
        }

        @Override
        public void release(UUID reservationId, String reason) {
            released.add(reservationId);
        }

        @Override
        public void deduct(UUID orderId, List<Reservation> reservations) {
            deductCalls++;
            if (failDeduct != null) {
                throw failDeduct;
            }
            reservations.forEach(r -> deducted.add(r.reservationId()));
        }
    }

    static class FakePayment implements PaymentGateway {
        final UUID intentId = UUID.randomUUID();
        final List<UUID> createdFor = new ArrayList<>();
        final List<UUID> refunded = new ArrayList<>();
        String status = "REQUIRES_ACTION";
        PaymentSnapshot confirmResult;
        RuntimeException failCreate;

        @Override
        public PaymentSnapshot createIntent(UUID orderId, UUID customerId, BigDecimal amount, String currency, String provider) {
            if (failCreate != null) {
                throw failCreate;
            }
            if (!createdFor.contains(orderId)) {
                createdFor.add(orderId);
            }
            return new PaymentSnapshot(intentId, "REQUIRES_ACTION", "secret", null, null);
        }

        @Override
        public PaymentSnapshot confirm(UUID paymentId, String token) {
            var result = confirmResult != null ? confirmResult : new PaymentSnapshot(paymentId, "CAPTURED", null, null, null);
            status = result.status();
            return result;
        }

        @Override
        public PaymentSnapshot get(UUID paymentId, UUID customerId) {
            return new PaymentSnapshot(paymentId, status, null, null, null);
        }

        @Override
        public void refund(UUID orderId, String reason) {
            refunded.add(orderId);
        }
    }
}
