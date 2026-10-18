package com.learning.order_service.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PayOrderRequest(@NotBlank @Size(max = 200) String paymentMethodToken) {
}
