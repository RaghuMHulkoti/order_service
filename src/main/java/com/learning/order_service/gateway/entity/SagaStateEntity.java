package com.learning.order_service.gateway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

/** One checkout saga per order; {@code currentStep} is where a restart resumes. */
@Entity
@Table(name = "saga_state")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SagaStateEntity {

    @Id
    @Column(name = "saga_id")
    private UUID sagaId;

    @Column(name = "saga_type", nullable = false)
    private String sagaType;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "current_step", nullable = false)
    private String currentStep;

    @Column(name = "state", nullable = false)
    private String state;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "last_event_at", nullable = false)
    private Instant lastEventAt;

    @Column(name = "version", nullable = false)
    private long version;
}
