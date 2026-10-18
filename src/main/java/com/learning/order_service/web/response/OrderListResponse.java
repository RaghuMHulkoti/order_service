package com.learning.order_service.web.response;

import java.util.List;

/** {@code nextCursor} is null on the last page. */
public record OrderListResponse(List<OrderResponse> orders, String nextCursor) {
}
