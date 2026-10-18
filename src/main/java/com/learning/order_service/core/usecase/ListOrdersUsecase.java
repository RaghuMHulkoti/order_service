package com.learning.order_service.core.usecase;

import com.learning.order_service.core.domain.Order;
import com.learning.order_service.core.domain.OrderStatus;
import com.learning.order_service.exception.BusinessRuleViolationException;
import com.learning.order_service.gateway.OrderGateway;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** Order history, newest first, with an opaque keyset cursor so pages stay stable while new orders arrive. */
public class ListOrdersUsecase implements Usecase<ListOrdersUsecase.Request, ListOrdersUsecase.Response> {

    public static final int MAX_PAGE_SIZE = 50;

    public record Request(UUID customerId, OrderStatus status, String cursor, int size) implements UsecaseRequest {
    }

    /** {@code nextCursor} is null on the last page. */
    public record Response(List<Order> orders, String nextCursor) implements UsecaseResponse {
    }

    private final OrderGateway orders;

    public ListOrdersUsecase(OrderGateway orders) {
        this.orders = orders;
    }

    @Override
    public Response execute(Request request) {
        int size = Math.max(1, Math.min(request.size(), MAX_PAGE_SIZE));
        Instant before = null;
        UUID beforeId = null;
        if (request.cursor() != null) {
            try {
                var parts = new String(Base64.getUrlDecoder().decode(request.cursor()), StandardCharsets.UTF_8).split("\\|");
                before = Instant.parse(parts[0]);
                beforeId = UUID.fromString(parts[1]);
            } catch (RuntimeException e) {
                throw new BusinessRuleViolationException("INVALID_CURSOR", "The cursor is not valid");
            }
        }
        var page = orders.list(request.customerId(), request.status(), before, beforeId, size + 1);
        if (page.size() <= size) {
            return new Response(page, null);
        }
        var shown = page.subList(0, size);
        var last = shown.getLast();
        var cursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((last.placedAt() + "|" + last.id()).getBytes(StandardCharsets.UTF_8));
        return new Response(shown, cursor);
    }
}
