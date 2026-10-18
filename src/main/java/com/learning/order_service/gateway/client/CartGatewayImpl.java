package com.learning.order_service.gateway.client;

import com.learning.order_service.core.domain.CartSnapshot;
import com.learning.order_service.gateway.CartGateway;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Calls Cart Service's customer API, impersonating the customer with the same X-User-Id the gateway would set. */
public class CartGatewayImpl implements CartGateway {

    private static final String USER_HEADER = "X-User-Id";

    private final RestClient cartRestClient;

    public CartGatewayImpl(RestClient cartRestClient) {
        this.cartRestClient = cartRestClient;
    }

    @Override
    public CartSnapshot fetch(UUID customerId) {
        try {
            var envelope = cartRestClient.get()
                    .uri("/api/v1/cart")
                    .header(USER_HEADER, customerId.toString())
                    .retrieve()
                    .body(new ParameterizedTypeReference<Envelope>() {});
            if (envelope == null || envelope.body() == null) {
                throw new IllegalStateException("Cart Service returned an empty body");
            }
            var cart = envelope.body();
            var lines = cart.items() == null ? List.<CartSnapshot.Line>of() : cart.items().stream()
                    .map(l -> new CartSnapshot.Line(l.itemId(), l.productId(), l.supplierId(), l.title(), l.quantity(),
                            l.unitPrice()))
                    .toList();
            return new CartSnapshot(lines, cart.currency(), cart.checkoutReady());
        } catch (RestClientException e) {
            throw Downstream.failure("Cart Service", e);
        }
    }

    @Override
    public void removeLine(UUID customerId, UUID offerId) {
        try {
            cartRestClient.delete()
                    .uri("/api/v1/cart/items/{itemId}", offerId)
                    .header(USER_HEADER, customerId.toString())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw Downstream.failure("Cart Service", e);
        }
    }

    record Envelope(Cart body) {}
    record Cart(List<Line> items, String currency, boolean checkoutReady) {}
    record Line(UUID itemId, UUID productId, UUID supplierId, String title, int quantity, BigDecimal unitPrice) {}
}
