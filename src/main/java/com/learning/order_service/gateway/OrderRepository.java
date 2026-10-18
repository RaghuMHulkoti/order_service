package com.learning.order_service.gateway;

import com.learning.order_service.gateway.entity.OrderEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {

    Optional<OrderEntity> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);

    List<OrderEntity> findByStatusInOrderByPlacedAt(Collection<String> statuses, Limit limit);

    // History pages, newest first. Four fixed queries instead of one with nullable parameters: a null cursor or
    // status bound without a type is not portable across Oracle and H2.

    List<OrderEntity> findByCustomerIdOrderByPlacedAtDescIdDesc(UUID customerId, Limit limit);

    List<OrderEntity> findByCustomerIdAndStatusOrderByPlacedAtDescIdDesc(UUID customerId, String status, Limit limit);

    @Query("""
            SELECT o FROM OrderEntity o
            WHERE o.customerId = :customerId AND (o.placedAt < :before OR (o.placedAt = :before AND o.id < :beforeId))
            ORDER BY o.placedAt DESC, o.id DESC""")
    List<OrderEntity> pageAfter(@Param("customerId") UUID customerId, @Param("before") Instant before,
                                @Param("beforeId") UUID beforeId, Limit limit);

    @Query("""
            SELECT o FROM OrderEntity o
            WHERE o.customerId = :customerId AND o.status = :status
              AND (o.placedAt < :before OR (o.placedAt = :before AND o.id < :beforeId))
            ORDER BY o.placedAt DESC, o.id DESC""")
    List<OrderEntity> pageAfterWithStatus(@Param("customerId") UUID customerId, @Param("status") String status,
                                          @Param("before") Instant before, @Param("beforeId") UUID beforeId, Limit limit);

    @Query(value = "SELECT {h-schema}order_number_seq.NEXTVAL FROM DUAL", nativeQuery = true)
    long nextOrderSequence();
}
