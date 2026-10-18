package com.learning.order_service.gateway.client;

import com.learning.order_service.core.domain.PaymentSnapshot;
import com.learning.order_service.gateway.PaymentGateway;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.UUID;

/** Calls Payment Service's internal intent and refund API (TDD §11.8) and its owner-scoped status route. */
public class PaymentGatewayImpl implements PaymentGateway {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final RestClient paymentRestClient;

    public PaymentGatewayImpl(RestClient paymentRestClient) {
        this.paymentRestClient = paymentRestClient;
    }

    @Override
    public PaymentSnapshot createIntent(UUID orderId, UUID customerId, BigDecimal amount, String currency, String provider) {
        return call(() -> paymentRestClient.post()
                .uri("/api/v1/internal/payments/intents")
                .header(IDEMPOTENCY_HEADER, "order_" + orderId)
                .body(new IntentBody(orderId, customerId, amount, currency, provider))
                .retrieve()
                .body(new ParameterizedTypeReference<Envelope>() {}));
    }

    @Override
    public PaymentSnapshot confirm(UUID paymentId, String paymentMethodToken) {
        return call(() -> paymentRestClient.post()
                .uri("/api/v1/internal/payments/intents/{id}/confirm", paymentId)
                .body(new ConfirmBody(paymentMethodToken))
                .retrieve()
                .body(new ParameterizedTypeReference<Envelope>() {}));
    }

    @Override
    public PaymentSnapshot get(UUID paymentId, UUID customerId) {
        return call(() -> paymentRestClient.get()
                .uri("/api/v1/payments/{id}", paymentId)
                .header("X-User-Id", customerId.toString())
                .retrieve()
                .body(new ParameterizedTypeReference<Envelope>() {}));
    }

    @Override
    public void refund(UUID orderId, String reason) {
        try {
            paymentRestClient.post()
                    .uri("/api/v1/internal/payments/refunds")
                    .header(IDEMPOTENCY_HEADER, "refund_order_" + orderId)
                    .body(new RefundBody(orderId, reason))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw Downstream.failure("Payment Service", e);
        }
    }

    private static PaymentSnapshot call(java.util.function.Supplier<Envelope> request) {
        try {
            var envelope = request.get();
            if (envelope == null || envelope.body() == null) {
                throw new IllegalStateException("Payment Service returned an empty body");
            }
            var p = envelope.body();
            return new PaymentSnapshot(p.paymentId(), p.status(), p.clientSecret(), p.failureCode(), p.failureMessage());
        } catch (RestClientException e) {
            throw Downstream.failure("Payment Service", e);
        }
    }

    record IntentBody(UUID orderId, UUID customerId, BigDecimal amount, String currency, String provider) {}
    record ConfirmBody(String paymentMethodToken) {}
    record RefundBody(UUID orderId, String reason) {}
    record Envelope(Payment body) {}
    record Payment(UUID paymentId, String status, String clientSecret, String failureCode, String failureMessage) {}
}
