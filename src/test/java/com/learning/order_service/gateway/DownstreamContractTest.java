package com.learning.order_service.gateway;

import com.learning.order_service.core.domain.Reservation;
import com.learning.order_service.exception.BusinessRuleViolationException;
import com.learning.order_service.exception.DependencyUnavailableException;
import com.learning.order_service.gateway.client.CartGatewayImpl;
import com.learning.order_service.gateway.client.InventoryGatewayImpl;
import com.learning.order_service.gateway.client.PaymentGatewayImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Pins the exact paths, headers and bodies this service sends to Cart, Inventory and Payment, and how it reads their
 * envelopes. The JSON below mirrors those services' real responses.
 */
class DownstreamContractTest {

    final UUID customer = UUID.randomUUID();
    final UUID order = UUID.randomUUID();
    final UUID offer = UUID.randomUUID();
    final UUID warehouse = UUID.randomUUID();
    final UUID item = UUID.randomUUID();
    final UUID reservation = UUID.randomUUID();

    MockRestServiceServer server;
    RestClient client;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl("http://downstream");
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
    }

    @Test
    void cartIsReadAsTheCustomer() {
        server.expect(requestTo("http://downstream/api/v1/cart")).andExpect(header("X-User-Id", customer.toString()))
                .andRespond(withSuccess("""
                        {"status":"SUCCESS","body":{"cartId":"%s","currency":"INR","checkoutReady":true,"subtotal":100,
                         "items":[{"itemId":"%s","productId":"%s","supplierId":"%s","title":"Keyboard","quantity":2,
                         "unitPrice":50.00,"availability":"IN_STOCK"}]}}""".formatted(customer, offer, UUID.randomUUID(),
                        UUID.randomUUID()), MediaType.APPLICATION_JSON));

        var cart = new CartGatewayImpl(client).fetch(customer);

        assertThat(cart.checkoutReady()).isTrue();
        assertThat(cart.currency()).isEqualTo("INR");
        assertThat(cart.lines()).singleElement().satisfies(l -> {
            assertThat(l.offerId()).isEqualTo(offer);
            assertThat(l.unitPrice()).isEqualByComparingTo("50");
            assertThat(l.quantity()).isEqualTo(2);
        });
    }

    @Test
    void cartLineIsRemovedByOfferId() {
        server.expect(requestTo("http://downstream/api/v1/cart/items/" + offer)).andExpect(method(HttpMethod.DELETE))
                .andExpect(header("X-User-Id", customer.toString())).andRespond(withSuccess());

        new CartGatewayImpl(client).removeLine(customer, offer);

        server.verify();
    }

    @Test
    void inventoryStockIsReadPerWarehouse() {
        server.expect(requestTo("http://downstream/api/v1/inventory/" + offer)).andRespond(withSuccess("""
                {"status":"SUCCESS","body":{"inventories":[{"warehouseId":"%s","supplierProductId":"%s","quantityOnHand":9,
                 "quantityReserved":2,"availableQuantity":7}]}}""".formatted(warehouse, offer), MediaType.APPLICATION_JSON));

        var stock = new InventoryGatewayImpl(client).stockFor(offer);

        assertThat(stock).singleElement().satisfies(s -> {
            assertThat(s.warehouseId()).isEqualTo(warehouse);
            assertThat(s.available()).isEqualTo(7);
        });
    }

    @Test
    void unknownOfferHasNoStock() {
        server.expect(requestTo("http://downstream/api/v1/inventory/" + offer)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(new InventoryGatewayImpl(client).stockFor(offer)).isEmpty();
    }

    @Test
    void reserveSendsIdempotencyKeyAndReadsReservations() {
        server.expect(requestTo("http://downstream/api/v1/inventory/reserve")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "order_" + order))
                .andExpect(jsonPath("$.orderId").value(order.toString()))
                .andExpect(jsonPath("$.ttlSeconds").value(1800))
                .andExpect(jsonPath("$.items[0].orderItemId").value(item.toString()))
                .andExpect(jsonPath("$.items[0].supplierProductId").value(offer.toString()))
                .andExpect(jsonPath("$.items[0].warehouseId").value(warehouse.toString()))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andRespond(withSuccess("""
                        {"status":"SUCCESS","body":{"reservations":[{"reservationId":"%s","orderId":"%s","orderItemId":"%s",
                         "status":"HELD"}]}}""".formatted(reservation, order, item), MediaType.APPLICATION_JSON));

        var held = new InventoryGatewayImpl(client).reserve(order,
                List.of(new InventoryGateway.ReserveLine(item, offer, warehouse, 2)), Duration.ofMinutes(30));

        assertThat(held).containsExactly(new Reservation(item, reservation));
    }

    @Test
    void insufficientStockSurfacesInventoryErrorCode() {
        server.expect(requestTo("http://downstream/api/v1/inventory/reserve")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .contentType(MediaType.APPLICATION_JSON).body("""
                        {"status":"FAILURE","message":"x","body":{"errorCode":"INSUFFICIENT_STOCK","message":"Insufficient stock"}}"""));

        assertThatThrownBy(() -> new InventoryGatewayImpl(client).reserve(order,
                List.of(new InventoryGateway.ReserveLine(item, offer, warehouse, 2)), Duration.ofMinutes(30)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("INSUFFICIENT_STOCK"));
    }

    @Test
    void serverErrorMeansDependencyUnavailable() {
        server.expect(requestTo("http://downstream/api/v1/inventory/deduct")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> new InventoryGatewayImpl(client).deduct(order, List.of(new Reservation(item, reservation))))
                .isInstanceOf(DependencyUnavailableException.class);
    }

    @Test
    void deductAndReleaseUseInventoryBodies() {
        server.expect(requestTo("http://downstream/api/v1/inventory/deduct"))
                .andExpect(jsonPath("$.orderId").value(order.toString()))
                .andExpect(jsonPath("$.items[0].orderItemId").value(item.toString()))
                .andExpect(jsonPath("$.items[0].reservationId").value(reservation.toString())).andRespond(withSuccess());
        server.expect(requestTo("http://downstream/api/v1/inventory/release"))
                .andExpect(jsonPath("$.reservationId").value(reservation.toString()))
                .andExpect(jsonPath("$.reason").value("CUSTOMER_REQUEST")).andRespond(withSuccess());

        var inventory = new InventoryGatewayImpl(client);
        inventory.deduct(order, List.of(new Reservation(item, reservation)));
        inventory.release(reservation, "CUSTOMER_REQUEST");

        server.verify();
    }

    @Test
    void paymentIntentIsKeyedByOrder() {
        var intent = UUID.randomUUID();
        server.expect(requestTo("http://downstream/api/v1/internal/payments/intents")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "order_" + order))
                .andExpect(jsonPath("$.orderId").value(order.toString()))
                .andExpect(jsonPath("$.customerId").value(customer.toString()))
                .andExpect(jsonPath("$.amount").value(120.5))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andRespond(withSuccess("""
                        {"status":"SUCCESS","body":{"paymentId":"%s","orderId":"%s","status":"REQUIRES_ACTION",
                         "clientSecret":"pi_sim_x_secret_sim"}}""".formatted(intent, order), MediaType.APPLICATION_JSON));

        var payment = new PaymentGatewayImpl(client).createIntent(order, customer, new BigDecimal("120.50"), "INR", null, 1);

        assertThat(payment.paymentId()).isEqualTo(intent);
        assertThat(payment.clientSecret()).isEqualTo("pi_sim_x_secret_sim");
        assertThat(payment.isPaid()).isFalse();
    }

    @Test
    void paymentStatusIsReadAsOwnerAndRefundIsKeyedByOrder() {
        var intent = UUID.randomUUID();
        server.expect(requestTo("http://downstream/api/v1/payments/" + intent)).andExpect(header("X-User-Id", customer.toString()))
                .andRespond(withSuccess("""
                        {"status":"SUCCESS","body":{"paymentId":"%s","status":"CAPTURED"}}""".formatted(intent),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://downstream/api/v1/internal/payments/refunds"))
                .andExpect(header("Idempotency-Key", "refund_order_" + order))
                .andExpect(content().json("{\"orderId\":\"%s\",\"reason\":\"FULFILMENT_FAILED\"}".formatted(order)))
                .andRespond(withSuccess());

        var gateway = new PaymentGatewayImpl(client);
        assertThat(gateway.get(intent, customer).isPaid()).isTrue();
        gateway.refund(order, "FULFILMENT_FAILED");

        server.verify();
    }

    @Test
    void confirmPostsTheToken() {
        var intent = UUID.randomUUID();
        server.expect(requestTo("http://downstream/api/v1/internal/payments/intents/" + intent + "/confirm"))
                .andExpect(jsonPath("$.paymentMethodToken").value("tok_ok"))
                .andRespond(withSuccess("""
                        {"status":"SUCCESS","body":{"paymentId":"%s","status":"FAILED","failureCode":"card_declined"}}"""
                        .formatted(intent), MediaType.APPLICATION_JSON));

        var payment = new PaymentGatewayImpl(client).confirm(intent, "tok_ok");

        assertThat(payment.isDead()).isTrue();
        assertThat(payment.failureCode()).isEqualTo("card_declined");
    }
}
