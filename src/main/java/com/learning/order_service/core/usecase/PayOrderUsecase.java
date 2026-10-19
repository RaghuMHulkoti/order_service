package com.learning.order_service.core.usecase;

import com.learning.order_service.config.OrderProperties;
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
 * captured payment confirms the order, a payment that needs customer action leaves it waiting, and a decline leaves it
 * waiting too while attempts remain, so the customer can pay again with another method. Each retry opens a new
 * payment intent (the old one is dead). When attempts run out the order fails and its stock is freed.
 * Paying an already confirmed order is a no-op.
 */
public class PayOrderUsecase implements Usecase<PayOrderUsecase.Request, PayOrderUsecase.Response> {

    public record Request(UUID customerId, UUID orderId, String paymentMethodToken) implements UsecaseRequest {
    }

    /** {@code canRetry}: the payment failed and the order is still waiting, so another attempt is allowed. */
    public record Response(Order order, PaymentSnapshot payment, boolean canRetry) implements UsecaseResponse {
    }

    private final OrderGateway orders;
    private final PaymentGateway payments;
    private final OrderLifecycleService lifecycle;
    private final OrderProperties properties;

    public PayOrderUsecase(OrderGateway orders, PaymentGateway payments, OrderLifecycleService lifecycle,
                           OrderProperties properties) {
        this.orders = orders;
        this.payments = payments;
        this.lifecycle = lifecycle;
        this.properties = properties;
    }

    @Override
    public Response execute(Request request) {
        var order = orders.find(request.orderId()).filter(o -> o.customerId().equals(request.customerId()))
                .orElseThrow(() -> new OrderNotFoundException(request.orderId()));
        if (order.status() == OrderStatus.CONFIRMED) {
            return new Response(order, payments.get(order.paymentIntentId(), order.customerId()), false);
        }
        if (order.status() != OrderStatus.PAYMENT_PENDING || order.paymentIntentId() == null) {
            throw new BusinessRuleViolationException("INVALID_ORDER_STATE",
                    "Order is " + order.status() + " and cannot be paid");
        }

        var intentId = order.paymentIntentId();
        var current = payments.get(intentId, order.customerId());
        if (current.isDead()) {
            if (!lifecycle.canRetry(order, current)) {
                throw new BusinessRuleViolationException("PAYMENT_ATTEMPTS_EXHAUSTED",
                        "No payment attempts left for this order");
            }
            var fresh = payments.createIntent(order.id(), order.customerId(), order.total(), order.currency(),
                    order.paymentProvider(), order.paymentAttempts() + 1);
            orders.startPaymentAttempt(order.id(), fresh.paymentId());
            intentId = fresh.paymentId();
            order = orders.find(order.id()).orElse(order);
        }

        var payment = payments.confirm(intentId, request.paymentMethodToken());
        var settled = lifecycle.execute(OrderLifecycleServiceRequest.builder()
                .operation(OrderLifecycleServiceRequest.Operation.SETTLE).order(order).payment(payment).build()).getOrder();
        boolean canRetry = settled.status() == OrderStatus.PAYMENT_PENDING && lifecycle.canRetry(settled, payment);
        return new Response(settled, payment, canRetry);
    }
}
