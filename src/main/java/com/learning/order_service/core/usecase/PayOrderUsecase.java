package com.learning.order_service.core.usecase;

import com.learning.order_service.core.domain.Order;
import com.learning.order_service.core.domain.OrderStatus;
import com.learning.order_service.core.domain.PaymentSnapshot;
import com.learning.order_service.core.service.lifecycle.OrderLifecycleService;
import com.learning.order_service.core.service.lifecycle.OrderLifecycleServiceRequest;
import com.learning.order_service.exception.BusinessRuleViolationException;
import com.learning.order_service.exception.OrderNotFoundException;
import com.learning.order_service.gateway.OrderGateway;
import com.learning.order_service.gateway.PaymentGateway;

import java.util.UUID;

/**
 * Pays an order server-side with a payment-method token (the optional path of TDD §11.8), then settles it: a
 * captured payment confirms the order, a decline fails it and frees the stock, and a payment that needs customer
 * action leaves it waiting. Paying an already confirmed order is a no-op.
 */
public class PayOrderUsecase implements Usecase<PayOrderUsecase.Request, PayOrderUsecase.Response> {

    public record Request(UUID customerId, UUID orderId, String paymentMethodToken) implements UsecaseRequest {
    }

    public record Response(Order order, PaymentSnapshot payment) implements UsecaseResponse {
    }

    private final OrderGateway orders;
    private final PaymentGateway payments;
    private final OrderLifecycleService lifecycle;

    public PayOrderUsecase(OrderGateway orders, PaymentGateway payments, OrderLifecycleService lifecycle) {
        this.orders = orders;
        this.payments = payments;
        this.lifecycle = lifecycle;
    }

    @Override
    public Response execute(Request request) {
        var order = orders.find(request.orderId()).filter(o -> o.customerId().equals(request.customerId()))
                .orElseThrow(() -> new OrderNotFoundException(request.orderId()));
        if (order.status() == OrderStatus.CONFIRMED) {
            return new Response(order, payments.get(order.paymentIntentId(), order.customerId()));
        }
        if (order.status() != OrderStatus.PAYMENT_PENDING || order.paymentIntentId() == null) {
            throw new BusinessRuleViolationException("INVALID_ORDER_STATE",
                    "Order is " + order.status() + " and cannot be paid");
        }
        var payment = payments.confirm(order.paymentIntentId(), request.paymentMethodToken());
        var settled = lifecycle.execute(OrderLifecycleServiceRequest.builder()
                .operation(OrderLifecycleServiceRequest.Operation.SETTLE).order(order).payment(payment).build()).getOrder();
        return new Response(settled, payment);
    }
}
