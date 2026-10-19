package com.learning.order_service.web;

import com.learning.order_service.core.domain.Address;
import com.learning.order_service.core.domain.Order;
import com.learning.order_service.core.domain.OrderItem;
import com.learning.order_service.core.domain.OrderStatus;
import com.learning.order_service.core.usecase.CancelOrderUsecase;
import com.learning.order_service.core.usecase.GetOrderUsecase;
import com.learning.order_service.core.usecase.ListOrdersUsecase;
import com.learning.order_service.core.usecase.PayOrderUsecase;
import com.learning.order_service.core.usecase.PlaceOrderUsecase;
import com.learning.order_service.exception.BusinessRuleViolationException;
import com.learning.order_service.exception.GlobalExceptionHandler;
import com.learning.order_service.exception.OrderNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderControllerTest {

    static final String BODY = """
            {"shippingAddress":{"name":"Asha","line1":"1 MG Road","city":"Bengaluru","postalCode":"560001","country":"IN"}}""";

    final UUID user = UUID.randomUUID();
    PlaceOrderUsecase place = Mockito.mock(PlaceOrderUsecase.class);
    PayOrderUsecase pay = Mockito.mock(PayOrderUsecase.class);
    CancelOrderUsecase cancel = Mockito.mock(CancelOrderUsecase.class);
    GetOrderUsecase get = Mockito.mock(GetOrderUsecase.class);
    ListOrdersUsecase list = Mockito.mock(ListOrdersUsecase.class);
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new OrderController(place, pay, cancel, get, list))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    Order order(OrderStatus status) {
        var item = new OrderItem(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Keyboard",
                new BigDecimal("50"), 2, new BigDecimal("100"), "RESERVED", UUID.randomUUID(), UUID.randomUUID());
        return new Order(UUID.randomUUID(), "BZ-26101800001", user, status, "INR", new BigDecimal("100"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100"), new Address("Asha", "1 MG Road", null, "Bengaluru",
                null, "560001", "IN", null), null, UUID.randomUUID(), null, 1, "k", Instant.parse("2026-10-18T10:00:00Z"), null, null,
                null, List.of(item));
    }

    @Test
    void missingUserHeaderIs401() throws Exception {
        mvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.body.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void placeWithoutIdempotencyKeyIs400() throws Exception {
        mvc.perform(post("/api/v1/orders").header("X-User-Id", user.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.body.errorCode").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void placeWithoutShippingAddressIs400() throws Exception {
        mvc.perform(post("/api/v1/orders").header("X-User-Id", user.toString()).header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.body.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void placeReturns202WithNextActionAndReplayReturns200() throws Exception {
        var order = order(OrderStatus.PAYMENT_PENDING);
        when(place.execute(any())).thenReturn(new PlaceOrderUsecase.Response(order, "pi_secret", false));

        mvc.perform(post("/api/v1/orders").header("X-User-Id", user.toString()).header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.body.status").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.body.nextAction.kind").value("CONFIRM_PAYMENT"))
                .andExpect(jsonPath("$.body.nextAction.clientSecret").value("pi_secret"))
                .andExpect(jsonPath("$.body.items[0].title").value("Keyboard"));

        when(place.execute(any())).thenReturn(new PlaceOrderUsecase.Response(order, "pi_secret", true));
        mvc.perform(post("/api/v1/orders").header("X-User-Id", user.toString()).header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
    }

    @Test
    void businessRuleIs422AndUnknownOrderIs404() throws Exception {
        when(place.execute(any())).thenThrow(new BusinessRuleViolationException("CART_NOT_READY", "not ready"));
        mvc.perform(post("/api/v1/orders").header("X-User-Id", user.toString()).header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.body.errorCode").value("CART_NOT_READY"));

        var id = UUID.randomUUID();
        when(get.execute(any())).thenThrow(new OrderNotFoundException(id));
        mvc.perform(get("/api/v1/orders/" + id).header("X-User-Id", user.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void listReturnsPageAndCursor() throws Exception {
        when(list.execute(any())).thenReturn(new ListOrdersUsecase.Response(List.of(order(OrderStatus.CONFIRMED)), "next"));

        mvc.perform(get("/api/v1/orders?status=CONFIRMED&size=1").header("X-User-Id", user.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.orders[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.body.nextCursor").value("next"));
    }

    @Test
    void payRequiresTokenAndReturnsPaymentOutcome() throws Exception {
        var id = UUID.randomUUID();
        mvc.perform(post("/api/v1/orders/" + id + "/pay").header("X-User-Id", user.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        when(pay.execute(any())).thenReturn(new PayOrderUsecase.Response(order(OrderStatus.FAILED),
                new com.learning.order_service.core.domain.PaymentSnapshot(id, "FAILED", null, "card_declined", "declined"), false));
        mvc.perform(post("/api/v1/orders/" + id + "/pay").header("X-User-Id", user.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paymentMethodToken\":\"tok_fail\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.order.status").value("FAILED"))
                .andExpect(jsonPath("$.body.failureCode").value("card_declined"));
    }
}
