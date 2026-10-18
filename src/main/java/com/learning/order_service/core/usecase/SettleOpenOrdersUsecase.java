package com.learning.order_service.core.usecase;

import com.learning.order_service.config.OrderProperties;
import com.learning.order_service.core.domain.Order;
import com.learning.order_service.core.domain.OrderStatus;
import com.learning.order_service.core.service.lifecycle.OrderLifecycleService;
import com.learning.order_service.core.service.lifecycle.OrderLifecycleServiceRequest;
import com.learning.order_service.gateway.OrderGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;

/**
 * Finishes sagas nobody is waiting on. Payment Service has no push channel to this service yet, so a payment that
 * completes through a webhook is found here by polling. An order still unpaid after the payment timeout is
 * cancelled and its stock released; an order stuck in CREATED (the request died mid-placement) is failed.
 * One bad order never stops the run.
 */
public class SettleOpenOrdersUsecase implements Usecase<SettleOpenOrdersUsecase.Request, SettleOpenOrdersUsecase.Response> {

    private static final Logger log = LoggerFactory.getLogger(SettleOpenOrdersUsecase.class);

    public record Request(int batchSize) implements UsecaseRequest {
    }

    public record Response(int settled, int expired) implements UsecaseResponse {
    }

    private final OrderGateway orders;
    private final OrderLifecycleService lifecycle;
    private final OrderProperties properties;
    private final Clock clock;

    public SettleOpenOrdersUsecase(OrderGateway orders, OrderLifecycleService lifecycle, OrderProperties properties, Clock clock) {
        this.orders = orders;
        this.lifecycle = lifecycle;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Response execute(Request request) {
        int settled = 0;
        int expired = 0;
        var deadline = clock.instant().minus(properties.paymentTimeout());
        for (var order : orders.findOpen(request.batchSize())) {
            try {
                var current = order;
                if (order.status() == OrderStatus.PAYMENT_PENDING) {
                    current = lifecycle.execute(OrderLifecycleServiceRequest.builder()
                            .operation(OrderLifecycleServiceRequest.Operation.SETTLE).order(order).build()).getOrder();
                    if (!current.status().isOpen()) {
                        settled++;
                        continue;
                    }
                }
                if (current.placedAt().isBefore(deadline)) {
                    expire(current);
                    expired++;
                }
            } catch (RuntimeException e) {
                log.warn("Order {} could not be settled this run: {}", order.id(), e.getMessage());
            }
        }
        return new Response(settled, expired);
    }

    private void expire(Order order) {
        boolean pending = order.status() == OrderStatus.PAYMENT_PENDING;
        lifecycle.execute(OrderLifecycleServiceRequest.builder()
                .operation(OrderLifecycleServiceRequest.Operation.ABORT).order(order)
                .target(pending ? OrderStatus.CANCELLED : OrderStatus.FAILED)
                .reason(pending ? "PAYMENT_TIMEOUT" : "PLACEMENT_INTERRUPTED").actor("system").build());
    }
}
