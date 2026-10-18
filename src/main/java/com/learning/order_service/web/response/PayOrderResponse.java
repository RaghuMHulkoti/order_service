package com.learning.order_service.web.response;

/** The settled order plus what the payment provider said, so a decline or a 3DS step is visible to the client. */
public record PayOrderResponse(OrderResponse order, String paymentStatus, String failureCode, String failureMessage) {
}
