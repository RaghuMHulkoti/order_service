package com.learning.order_service.core.service.lifecycle;

import com.learning.order_service.core.domain.Order;
import com.learning.order_service.core.service.ServiceResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** The order as it stands after the operation, re-read so callers never return a stale status. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderLifecycleServiceResponse implements ServiceResponse {

    private Order order;
}
