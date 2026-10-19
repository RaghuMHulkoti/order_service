package com.learning.order_service.web;

import com.learning.order_service.common.StatusCode;
import com.learning.order_service.core.domain.OrderStatus;
import com.learning.order_service.core.usecase.CancelOrderUsecase;
import com.learning.order_service.core.usecase.GetOrderUsecase;
import com.learning.order_service.core.usecase.ListOrdersUsecase;
import com.learning.order_service.core.usecase.PayOrderUsecase;
import com.learning.order_service.core.usecase.PlaceOrderUsecase;
import com.learning.order_service.web.request.PayOrderRequest;
import com.learning.order_service.web.request.PlaceOrderRequest;
import com.learning.order_service.web.response.OrderListResponse;
import com.learning.order_service.web.response.OrderResponse;
import com.learning.order_service.web.response.PayOrderResponse;
import com.learning.order_service.web.response.Response;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Customer order API (TDD §11.7). The caller is identified by {@code X-User-Id}, which the API gateway sets after
 * validating the JWT; this service is never exposed without the gateway in front of it.
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders")
public class OrderController {

    private static final String USER_HEADER = "X-User-Id";

    private final PlaceOrderUsecase placeOrder;
    private final PayOrderUsecase payOrder;
    private final CancelOrderUsecase cancelOrder;
    private final GetOrderUsecase getOrder;
    private final ListOrdersUsecase listOrders;

    public OrderController(PlaceOrderUsecase placeOrder, PayOrderUsecase payOrder, CancelOrderUsecase cancelOrder,
                           GetOrderUsecase getOrder, ListOrdersUsecase listOrders) {
        this.placeOrder = placeOrder;
        this.payOrder = payOrder;
        this.cancelOrder = cancelOrder;
        this.getOrder = getOrder;
        this.listOrders = listOrders;
    }

    @PostMapping
    @Operation(summary = "Place an order from the cart (starts the checkout saga). 202 when new, 200 on a replay of the same key")
    public ResponseEntity<Response<OrderResponse>> place(
            @Parameter(hidden = true) @RequestHeader(USER_HEADER) UUID userId,
            @Parameter(description = "Unique per checkout attempt, e.g. a UUID") @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest request) {
        var result = placeOrder.execute(new PlaceOrderUsecase.Request(userId, idempotencyKey,
                request.shippingAddress().toDomain(),
                request.billingAddress() == null ? null : request.billingAddress().toDomain(), request.provider()));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(new Response<>(StatusCode.SUCCESS, result.replayed() ? "Order already placed" : "Order placed",
                        OrderResponseMapper.toResponse(result.order(), result.clientSecret())));
    }

    @PostMapping("/{id}/pay")
    @Operation(summary = "Pay a PAYMENT_PENDING order with a payment-method token; a captured payment confirms the order")
    public ResponseEntity<Response<PayOrderResponse>> pay(
            @Parameter(hidden = true) @RequestHeader(USER_HEADER) UUID userId, @PathVariable UUID id,
            @Valid @RequestBody PayOrderRequest request) {
        var result = payOrder.execute(new PayOrderUsecase.Request(userId, id, request.paymentMethodToken()));
        var payment = result.payment();
        return ok("Order " + result.order().status().name().toLowerCase().replace('_', ' '),
                new PayOrderResponse(OrderResponseMapper.toResponse(result.order()), payment.status(),
                        payment.failureCode(), payment.failureMessage(), result.order().paymentAttempts(),
                        result.canRetry()));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an order that is not yet paid; its held stock is released")
    public ResponseEntity<Response<OrderResponse>> cancel(
            @Parameter(hidden = true) @RequestHeader(USER_HEADER) UUID userId, @PathVariable UUID id) {
        var result = cancelOrder.execute(new CancelOrderUsecase.Request(userId, id));
        return ok("Order cancelled", OrderResponseMapper.toResponse(result.order()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Order detail; only the owning customer can read it")
    public ResponseEntity<Response<OrderResponse>> get(
            @Parameter(hidden = true) @RequestHeader(USER_HEADER) UUID userId, @PathVariable UUID id) {
        var result = getOrder.execute(new GetOrderUsecase.Request(userId, id));
        return ok("Order retrieved successfully", OrderResponseMapper.toResponse(result.order()));
    }

    @GetMapping
    @Operation(summary = "Order history, newest first, with cursor pagination")
    public ResponseEntity<Response<OrderListResponse>> list(
            @Parameter(hidden = true) @RequestHeader(USER_HEADER) UUID userId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        var result = listOrders.execute(new ListOrdersUsecase.Request(userId, status, cursor, size));
        return ok("Orders retrieved successfully", new OrderListResponse(
                result.orders().stream().map(OrderResponseMapper::toResponse).toList(), result.nextCursor()));
    }

    private static <T> ResponseEntity<Response<T>> ok(String message, T body) {
        return ResponseEntity.ok(new Response<>(StatusCode.SUCCESS, message, body));
    }
}
