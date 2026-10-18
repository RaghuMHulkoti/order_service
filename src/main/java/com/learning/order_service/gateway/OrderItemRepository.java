package com.learning.order_service.gateway;

import com.learning.order_service.gateway.entity.OrderItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItemEntity, UUID> {

    List<OrderItemEntity> findByOrderIdOrderById(UUID orderId);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE OrderItemEntity i SET i.reservationId = :reservationId, i.fulfilmentStatus = 'RESERVED' WHERE i.id = :id AND i.orderId = :orderId")
    int reserve(@Param("id") UUID id, @Param("orderId") UUID orderId, @Param("reservationId") UUID reservationId);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE OrderItemEntity i SET i.fulfilmentStatus = :status WHERE i.orderId = :orderId")
    int setStatusForOrder(@Param("orderId") UUID orderId, @Param("status") String status);
}
