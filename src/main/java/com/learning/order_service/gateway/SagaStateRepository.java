package com.learning.order_service.gateway;

import com.learning.order_service.gateway.entity.SagaStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SagaStateRepository extends JpaRepository<SagaStateEntity, UUID> {

    Optional<SagaStateEntity> findByOrderId(UUID orderId);
}
