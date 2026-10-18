package com.learning.order_service.gateway;

import com.learning.order_service.core.domain.Address;
import com.learning.order_service.core.domain.Order;
import com.learning.order_service.core.domain.OrderItem;
import com.learning.order_service.core.domain.OrderStatus;
import com.learning.order_service.core.domain.Reservation;
import com.learning.order_service.exception.DependencyUnavailableException;
import com.learning.order_service.exception.DuplicateOrderException;
import com.learning.order_service.gateway.entity.OrderEntity;
import com.learning.order_service.gateway.entity.OrderItemEntity;
import com.learning.order_service.gateway.entity.OrderOutboxEntity;
import com.learning.order_service.gateway.entity.OrderStatusHistoryEntity;
import com.learning.order_service.gateway.entity.SagaStateEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Oracle-backed order store (schema ORDERS) through JPA. Addresses are JSON text snapshots. */
public class JpaOrderGateway implements OrderGateway {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyMMdd").withZone(ZoneOffset.UTC);
    private static final List<String> OPEN = List.of(OrderStatus.CREATED.name(), OrderStatus.PAYMENT_PENDING.name());

    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final OrderStatusHistoryRepository history;
    private final SagaStateRepository sagas;
    private final OrderOutboxRepository outbox;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public JpaOrderGateway(OrderRepository orders, OrderItemRepository items, OrderStatusHistoryRepository history,
                           SagaStateRepository sagas, OrderOutboxRepository outbox, TransactionTemplate tx, JsonMapper json) {
        this.orders = orders;
        this.items = items;
        this.history = history;
        this.sagas = sagas;
        this.outbox = outbox;
        this.tx = tx;
        this.json = json;
    }

    @Override
    public String nextOrderNumber(Instant now) {
        long seq = guard("number order", orders::nextOrderSequence);
        return "BZ-" + DAY.format(now) + String.format("%05d", seq);
    }

    @Override
    public void insert(Order o, Instant now) {
        try {
            guard("create order", () -> tx.execute(status -> {
                orders.saveAndFlush(OrderEntity.builder().id(o.id()).orderNumber(o.orderNumber())
                        .customerId(o.customerId()).status(o.status().name()).currency(o.currency())
                        .subtotal(o.subtotal()).discount(o.discount()).shippingFee(o.shippingFee()).tax(o.tax())
                        .total(o.total()).shippingAddress(json.writeValueAsString(o.shippingAddress()))
                        .billingAddress(o.billingAddress() == null ? null : json.writeValueAsString(o.billingAddress()))
                        .idempotencyKey(o.idempotencyKey()).placedAt(o.placedAt()).updatedAt(now).build());
                items.saveAll(o.items().stream().map(i -> OrderItemEntity.builder().id(i.id()).orderId(o.id())
                        .productId(i.productId()).supplierProductId(i.supplierProductId()).supplierId(i.supplierId())
                        .title(i.title()).unitPrice(i.unitPrice()).quantity(i.quantity()).lineTotal(i.lineTotal())
                        .fulfilmentStatus(i.fulfilmentStatus()).warehouseId(i.warehouseId()).build()).toList());
                recordHistory(o.id(), null, o.status(), null, "customer", now);
                sagas.save(SagaStateEntity.builder().sagaId(UUID.randomUUID()).sagaType("CHECKOUT").orderId(o.id())
                        .currentStep("RESERVE").state("RUNNING").lastEventAt(now).build());
                sagas.flush();
                return true;
            }));
        } catch (DataIntegrityViolationException e) {
            // the only unique key a caller can collide on is its own (customer, idempotency key)
            if (orders.findByCustomerIdAndIdempotencyKey(o.customerId(), o.idempotencyKey()).isPresent()) {
                throw new DuplicateOrderException();
            }
            throw new DependencyUnavailableException("Order store rejected the order", e);
        }
    }

    @Override
    public Optional<Order> find(UUID orderId) {
        return guard("read order", () -> orders.findById(orderId).map(this::withItems));
    }

    @Override
    public Optional<Order> findByIdempotencyKey(UUID customerId, String idempotencyKey) {
        return guard("read order", () -> orders.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey)
                .map(this::withItems));
    }

    @Override
    public List<Order> list(UUID customerId, OrderStatus status, Instant beforePlacedAt, UUID beforeId, int limit) {
        return guard("list orders", () -> {
            var max = Limit.of(limit);
            List<OrderEntity> page;
            if (beforePlacedAt == null) {
                page = status == null ? orders.findByCustomerIdOrderByPlacedAtDescIdDesc(customerId, max)
                        : orders.findByCustomerIdAndStatusOrderByPlacedAtDescIdDesc(customerId, status.name(), max);
            } else {
                page = status == null ? orders.pageAfter(customerId, beforePlacedAt, beforeId, max)
                        : orders.pageAfterWithStatus(customerId, status.name(), beforePlacedAt, beforeId, max);
            }
            return page.stream().map(e -> toDomain(e, List.of())).toList();
        });
    }

    @Override
    public List<Order> findOpen(int limit) {
        return guard("read open orders", () -> orders.findByStatusInOrderByPlacedAt(OPEN, Limit.of(limit)).stream()
                .map(this::withItems).toList());
    }

    @Override
    public void saveReservations(UUID orderId, List<Reservation> reservations) {
        guard("save reservations", () -> tx.execute(status -> {
            reservations.forEach(r -> items.reserve(r.orderItemId(), orderId, r.reservationId()));
            return null;
        }));
    }

    @Override
    public void setPaymentIntent(UUID orderId, UUID paymentIntentId) {
        guard("save payment intent", () -> tx.execute(status -> {
            orders.findById(orderId).ifPresent(e -> {
                e.setPaymentIntentId(paymentIntentId);
                orders.saveAndFlush(e);
            });
            return null;
        }));
    }

    @Override
    public boolean transition(Transition t) {
        try {
            return Boolean.TRUE.equals(guard("update order", () -> tx.execute(status -> {
                var order = orders.findById(t.orderId()).filter(e -> e.getStatus().equals(t.from().name())).orElse(null);
                if (order == null) {
                    return false;
                }
                order.setStatus(t.to().name());
                order.setUpdatedAt(t.now());
                if (t.to() == OrderStatus.CONFIRMED) {
                    order.setConfirmedAt(t.now());
                }
                if (t.to() == OrderStatus.CANCELLED) {
                    order.setCancelledAt(t.now());
                }
                if (t.to() == OrderStatus.CANCELLED || t.to() == OrderStatus.FAILED) {
                    order.setCancelReason(t.reason());
                }
                orders.saveAndFlush(order); // version check: a concurrent mover makes this throw
                recordHistory(t.orderId(), t.from(), t.to(), t.reason(), t.actor(), t.now());
                sagas.findByOrderId(t.orderId()).ifPresent(saga -> {
                    saga.setCurrentStep(t.sagaStep());
                    saga.setState(t.sagaState());
                    saga.setLastEventAt(t.now());
                    saga.setVersion(saga.getVersion() + 1);
                    sagas.save(saga);
                });
                if (t.itemStatus() != null) {
                    items.setStatusForOrder(t.orderId(), t.itemStatus());
                }
                if (t.eventType() != null) {
                    outbox.save(OrderOutboxEntity.builder().aggregateId(t.orderId()).eventType(t.eventType())
                            .payload(t.eventPayload()).status("PENDING").createdAt(t.now()).build());
                }
                return true;
            })));
        } catch (OptimisticLockingFailureException lostRace) {
            return false;
        }
    }

    private void recordHistory(UUID orderId, OrderStatus from, OrderStatus to, String reason, String actor, Instant at) {
        history.save(OrderStatusHistoryEntity.builder().orderId(orderId).fromStatus(from == null ? null : from.name())
                .toStatus(to.name()).reason(reason).actor(actor).createdAt(at).build());
    }

    private Order withItems(OrderEntity e) {
        return toDomain(e, items.findByOrderIdOrderById(e.getId()).stream().map(JpaOrderGateway::toDomain).toList());
    }

    private Order toDomain(OrderEntity e, List<OrderItem> lines) {
        return new Order(e.getId(), e.getOrderNumber(), e.getCustomerId(), OrderStatus.valueOf(e.getStatus()),
                e.getCurrency().trim(), e.getSubtotal(), e.getDiscount(), e.getShippingFee(), e.getTax(), e.getTotal(),
                json.readValue(e.getShippingAddress(), Address.class),
                e.getBillingAddress() == null ? null : json.readValue(e.getBillingAddress(), Address.class),
                e.getPaymentIntentId(), e.getIdempotencyKey(), e.getPlacedAt(), e.getConfirmedAt(), e.getCancelledAt(),
                e.getCancelReason(), lines);
    }

    private static OrderItem toDomain(OrderItemEntity e) {
        return new OrderItem(e.getId(), e.getProductId(), e.getSupplierProductId(), e.getSupplierId(), e.getTitle(),
                e.getUnitPrice(), e.getQuantity(), e.getLineTotal(), e.getFulfilmentStatus(), e.getWarehouseId(),
                e.getReservationId());
    }

    private <T> T guard(String action, Supplier<T> call) {
        try {
            return call.get();
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException e) {
            throw e;
        } catch (DataAccessException e) {
            throw new DependencyUnavailableException("Order store unavailable: could not " + action, e);
        }
    }
}
