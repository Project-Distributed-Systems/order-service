package com.ticket.order.application.dto;

import java.io.Serializable;

public record OrderConfirmedEvent(
    Long orderId,
    Long userId,
    Long eventId) implements Serializable {
}
