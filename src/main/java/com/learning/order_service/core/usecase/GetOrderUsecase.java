package com.learning.order_service.core.usecase;

import com.learning.order_service.core.domain.Order;
import com.learning.order_service.exception.OrderNotFoundException;
import com.learning.order_service.gateway.OrderGateway;

import java.util.UUID;

/** An order belongs to its customer: anyone else gets the same not-found as for a missing id. */
public class GetOrderUsecase implements Usecase<GetOrderUsecase.Request, GetOrderUsecase.Response> {

    public record Request(UUID customerId, UUID orderId) implements UsecaseRequest {
    }

    public record Response(Order order) implements UsecaseResponse {
    }

    private final OrderGateway orders;

    public GetOrderUsecase(OrderGateway orders) {
        this.orders = orders;
    }

    @Override
    public Response execute(Request request) {
        return new Response(orders.find(request.orderId()).filter(o -> o.customerId().equals(request.customerId()))
                .orElseThrow(() -> new OrderNotFoundException(request.orderId())));
    }
}
