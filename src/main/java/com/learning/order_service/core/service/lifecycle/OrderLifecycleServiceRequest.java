package com.learning.order_service.core.service.lifecycle;

import com.learning.order_service.core.domain.Order;
import com.learning.order_service.core.domain.OrderStatus;
import com.learning.order_service.core.domain.PaymentSnapshot;
import com.learning.order_service.core.service.ServiceRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderLifecycleServiceRequest implements ServiceRequest {

    private Operation operation;
    private Order order;
    /** SETTLE: the payment state the caller already has; fetched from Payment Service when null. */
    private PaymentSnapshot payment;
    /** ABORT: CANCELLED or FAILED. */
    private OrderStatus target;
    private String reason;
    private String actor;

    public enum Operation {
        SETTLE, ABORT
    }
}
