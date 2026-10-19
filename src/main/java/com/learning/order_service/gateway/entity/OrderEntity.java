package com.learning.order_service.gateway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** {@code version} is a real JPA version: two requests moving the same order cannot both win. */
@Entity
@Table(name = "orders")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderEntity {

    @Id
    private UUID id;

    @Column(name = "order_number", nullable = false)
    private String orderNumber;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "subtotal", nullable = false)
    private BigDecimal subtotal;

    @Column(name = "discount", nullable = false)
    private BigDecimal discount;

    @Column(name = "shipping_fee", nullable = false)
    private BigDecimal shippingFee;

    @Column(name = "tax", nullable = false)
    private BigDecimal tax;

    @Column(name = "total", nullable = false)
    private BigDecimal total;

    /** JSON snapshot. */
    @Column(name = "shipping_address", nullable = false)
    private String shippingAddress;

    /** JSON snapshot. */
    @Column(name = "billing_address")
    private String billingAddress;

    @Column(name = "payment_intent_id")
    private UUID paymentIntentId;

    /** Provider the customer chose at checkout (null = routed by currency); Payment needs it once stock is held. */
    @Column(name = "payment_provider")
    private String paymentProvider;

    @Column(name = "payment_attempts", nullable = false)
    private Integer paymentAttempts;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    // plain TIMESTAMP columns: bind as java.sql.Timestamp, not as a timestamp with time zone
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
