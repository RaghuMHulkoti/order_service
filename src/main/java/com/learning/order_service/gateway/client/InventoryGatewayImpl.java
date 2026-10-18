package com.learning.order_service.gateway.client;

import com.learning.order_service.core.domain.Reservation;
import com.learning.order_service.core.domain.WarehouseStock;
import com.learning.order_service.gateway.InventoryGateway;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Calls Inventory Service: stock lookup per offer, then the reserve / release / deduct trio of TDD §10.3. */
public class InventoryGatewayImpl implements InventoryGateway {

    private final RestClient inventoryRestClient;

    public InventoryGatewayImpl(RestClient inventoryRestClient) {
        this.inventoryRestClient = inventoryRestClient;
    }

    @Override
    public List<WarehouseStock> stockFor(UUID supplierProductId) {
        try {
            var envelope = inventoryRestClient.get()
                    .uri("/api/v1/inventory/{id}", supplierProductId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Envelope<Stock>>() {});
            if (envelope == null || envelope.body() == null || envelope.body().inventories() == null) {
                return List.of();
            }
            return envelope.body().inventories().stream()
                    .map(i -> new WarehouseStock(i.warehouseId(), i.availableQuantity() == null ? 0 : i.availableQuantity()))
                    .toList();
        } catch (HttpClientErrorException.NotFound e) {
            return List.of();
        } catch (RestClientException e) {
            throw Downstream.failure("Inventory Service", e);
        }
    }

    @Override
    public List<Reservation> reserve(UUID orderId, List<ReserveLine> lines, Duration ttl) {
        try {
            var envelope = inventoryRestClient.post()
                    .uri("/api/v1/inventory/reserve")
                    .header("Idempotency-Key", "order_" + orderId)
                    .body(new ReserveBody(orderId, lines, ttl.toSeconds()))
                    .retrieve()
                    .body(new ParameterizedTypeReference<Envelope<Reserved>>() {});
            if (envelope == null || envelope.body() == null || envelope.body().reservations() == null) {
                throw new IllegalStateException("Inventory Service returned no reservations");
            }
            return envelope.body().reservations().stream()
                    .map(r -> new Reservation(r.orderItemId(), r.reservationId()))
                    .toList();
        } catch (RestClientException e) {
            throw Downstream.failure("Inventory Service", e);
        }
    }

    @Override
    public void release(UUID reservationId, String reason) {
        try {
            inventoryRestClient.post()
                    .uri("/api/v1/inventory/release")
                    .body(new ReleaseBody(reservationId, reason))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            // nothing is held under this id any more, which is what releasing wants
        } catch (RestClientException e) {
            throw Downstream.failure("Inventory Service", e);
        }
    }

    @Override
    public void deduct(UUID orderId, List<Reservation> reservations) {
        try {
            inventoryRestClient.post()
                    .uri("/api/v1/inventory/deduct")
                    .body(new DeductBody(orderId, reservations))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw Downstream.failure("Inventory Service", e);
        }
    }

    record ReserveBody(UUID orderId, List<ReserveLine> items, long ttlSeconds) {}
    record ReleaseBody(UUID reservationId, String reason) {}
    record DeductBody(UUID orderId, List<Reservation> items) {}

    record Envelope<T>(T body) {}
    record Stock(List<Inventory> inventories) {}
    record Inventory(UUID warehouseId, Integer availableQuantity) {}
    record Reserved(List<ReservationRow> reservations) {}
    record ReservationRow(UUID reservationId, UUID orderItemId) {}
}
