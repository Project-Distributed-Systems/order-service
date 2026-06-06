package com.ticket.order.infrastructure.persistense;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

  List<OrderEntity> findByStatusAndExpiresAtBefore(OrderEntity.Status status, LocalDateTime threshold);
}
