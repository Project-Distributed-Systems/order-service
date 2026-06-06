package com.ticket.order.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticket.order.infrastructure.persistense.OrderEntity;
import com.ticket.order.infrastructure.persistense.OrderRepository;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class OrderService {

  private static final Logger log = LoggerFactory.getLogger(OrderService.class);

  private final OrderRepository orderRepository;
  private final EventServiceClient eventServiceClient;

  @Value("${reservation.ttl-minutes}")
  private int ttlMinutes;

  public OrderService(OrderRepository orderRepository,
      EventServiceClient eventServiceClient) {
    this.orderRepository = orderRepository;
    this.eventServiceClient = eventServiceClient;
  }

  @Transactional
  public OrderEntity createOrder(Long userId, Long eventId) {
    // 1 call event-service to atomically decrement inventory
    eventServiceClient.reserveTicket(eventId);

    // 2 only if that succeeded, persist the order
    LocalDateTime now = LocalDateTime.now();
    OrderEntity order = new OrderEntity(userId, eventId, now, now.plusMinutes(ttlMinutes));
    OrderEntity saved = orderRepository.save(order);

    log.info("Order {} created for user {} on event {}, expires at {}",
        saved.getId(), userId, eventId, saved.getExpiresAt());

    return saved;
  }

  public OrderEntity findById(Long id) {
    return orderRepository.findById(id)
        .orElseThrow(() -> new RuntimeException("Order not found: " + id));
  }

  public List<OrderEntity> findAll() {
    return orderRepository.findAll();
  }

  // runs every 60 seconds finding PENDING orders past their TTL and marks them
  // EXPIRED
  // this does NOT release inventory yet, that's only when RabbitMQ is set
  @Scheduled(fixedDelay = 60_000)
  @Transactional
  public void expireStaleReservations() {
    List<OrderEntity> stale = orderRepository.findByStatusAndExpiresAtBefore(
        OrderEntity.Status.PENDING, LocalDateTime.now());

    if (!stale.isEmpty()) {
      log.warn("Expiring {} stale reservations", stale.size());
      stale.forEach(o -> o.setStatus(OrderEntity.Status.EXPIRED));
      orderRepository.saveAll(stale);
    }
  }
}
