package com.learning.order_service.core.usecase;

import com.learning.order_service.core.domain.Order;
import com.learning.order_service.core.domain.OrderStatus;
import com.learning.order_service.core.service.lifecycle.OrderLifecycleService;
import com.learning.order_service.core.service.lifecycle.OrderLifecycleServiceRequest;
import com.learning.order_service.exception.BusinessRuleViolationException;
import com.learning.order_service.exception.OrderNotFoundException;
import com.learning.order_service.gateway.OrderGateway;

import java.util.UUID;

/**
 * Customer cancel (TDD §12.12), allowed until payment settles. The payment is read first: if the money has already
 * arrived the order is confirmed instead of cancelled, so a cancel can never leave a captured payment behind.
 * Cancelling a cancelled order succeeds.
 */
public class CancelOrderUsecase implements Usecase<CancelOrderUsecase.Request, CancelOrderUsecase.Response> {

    public record Request(UUID customerId, UUID orderId) implements UsecaseRequest {
    }

    public record Response(Order order) implements UsecaseResponse {
    }

    private final OrderGateway orders;
    private final OrderLifecycleService lifecycle;

    public CancelOrderUsecase(OrderGateway orders, OrderLifecycleService lifecycle) {
        this.orders = orders;
        this.lifecycle = lifecycle;
    }

    @Override
    public Response execute(Request request) {
        var order = orders.find(request.orderId()).filter(o -> o.customerId().equals(request.customerId()))
                .orElseThrow(() -> new OrderNotFoundException(request.orderId()));
        if (order.status() == OrderStatus.CANCELLED) {
            return new Response(order);
        }
        if (order.status() == OrderStatus.PAYMENT_PENDING) {
            order = lifecycle.execute(OrderLifecycleServiceRequest.builder()
                    .operation(OrderLifecycleServiceRequest.Operation.SETTLE).order(order).build()).getOrder();
        }
        if (!order.status().isOpen()) {
            throw new BusinessRuleViolationException("ORDER_NOT_CANCELLABLE",
                    "Order is " + order.status() + " and can no longer be cancelled");
        }
        var cancelled = lifecycle.execute(OrderLifecycleServiceRequest.builder()
                .operation(OrderLifecycleServiceRequest.Operation.ABORT).order(order).target(OrderStatus.CANCELLED)
                .reason("CUSTOMER_REQUEST").actor("customer").build()).getOrder();
        return new Response(cancelled);
    }
}
