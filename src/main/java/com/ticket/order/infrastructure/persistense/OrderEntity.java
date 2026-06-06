package com.ticket.order.infrastructure.persistense;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "orders")
public class OrderEntity {

  public enum Status {
    PENDING, // reservation held, wait payment
    CONFIRMED, // payment confirmed
    EXPIRED, // TTL elapsed, reservation released
    FAILED // payment failed
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long userId;

  @Column(nullable = false)
  private Long eventId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status;

  @Column(nullable = false)
  private LocalDateTime createdAt;

  @Column(nullable = false)
  private LocalDateTime expiresAt;

  public OrderEntity() {
  }

  public OrderEntity(Long userId, Long eventId, LocalDateTime createdAt, LocalDateTime expiresAt) {
    this.userId = userId;
    this.eventId = eventId;
    this.status = Status.PENDING;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
  }

  public Long getId() {
    return id;
  }

  public Long getUserId() {
    return userId;
  }

  public Long getEventId() {
    return eventId;
  }

  public Status getStatus() {
    return status;
  }

  public void setStatus(Status status) {
    this.status = status;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public LocalDateTime getExpiresAt() {
    return expiresAt;
  }
}
