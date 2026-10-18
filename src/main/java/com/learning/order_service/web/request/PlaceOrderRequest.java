package com.learning.order_service.web.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** The items come from the customer's cart, so the request only carries where to ship and how to pay. */
public record PlaceOrderRequest(
        @NotNull @Valid AddressRequest shippingAddress,
        @Valid AddressRequest billingAddress,
        @Pattern(regexp = "STRIPE|RAZORPAY", message = "must be STRIPE or RAZORPAY") String provider
) {
}
