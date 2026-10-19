package com.learning.order_service.gateway;

import com.learning.order_service.gateway.entity.InboxEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface InboxEventRepository extends JpaRepository<InboxEventEntity, UUID> {
}
