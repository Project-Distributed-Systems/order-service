package com.ticket.order.infrastructure.messaging;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry; // ✅ já estava
import io.micrometer.core.instrument.Counter; // ✅ adicionar
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import com.ticket.order.application.dto.OrderConfirmedEvent;
import com.ticket.order.application.exceptions.GatewayUnavailableException;
import com.ticket.order.application.exceptions.InvalidOrderStateException;
import com.ticket.order.application.exceptions.OrderNotFoundException;
import com.ticket.order.application.exceptions.PaymentDeclinedException;
import com.ticket.order.infrastructure.persistense.OrderEntity;
import com.ticket.order.infrastructure.persistense.OrderRepository;
import java.math.BigDecimal;
import java.util.Map;

@Service
public class PaymentService {

  private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

  private final RestTemplate restTemplate;
  private final OrderRepository orderRepository;

  private final Counter ticketsSoldCounter;

  @Value("${payment-gateway.url}")
  private String gatewayUrl;

  private final RabbitTemplate rabbitTemplate;

  public PaymentService(
      RestTemplate restTemplate,
      OrderRepository orderRepository,
      RabbitTemplate rabbitTemplate,
      MeterRegistry meterRegistry) {
    this.restTemplate = restTemplate;
    this.orderRepository = orderRepository;
    this.rabbitTemplate = rabbitTemplate;

    this.ticketsSoldCounter = Counter.builder("tickets_sold_total")
        .description("Total tickets successfully sold")
        .register(meterRegistry);
  }

  @CircuitBreaker(name = "gateway", fallbackMethod = "chargeFallback")
  @Retry(name = "gateway")
  public void processPayment(Long orderId, String paymentMethod, BigDecimal amount) {
    OrderEntity order = orderRepository.findById(orderId)
        .orElseThrow(() -> new OrderNotFoundException(orderId));

    // entry guard: only PENDING orders can be paid
    if (order.getStatus() != OrderEntity.Status.PENDING) {
      throw new InvalidOrderStateException(orderId, order.getStatus());
    }

    String idempotencyKey = "order-" + orderId;

    Map<String, Object> request = Map.of(
        "idempotencyKey", idempotencyKey,
        "paymentMethod", paymentMethod,
        "orderId", orderId,
        "amount", amount);

    try {
      var response = restTemplate.postForEntity(
          gatewayUrl + "/charges", request, Map.class);

      String status = (String) response.getBody().get("status");

      if ("APPROVED".equals(status)) {
        order.setStatus(OrderEntity.Status.CONFIRMED);
        orderRepository.save(order);
        log.info("Payment approved for order {}", orderId);

        // publish async event into the broker
        OrderConfirmedEvent event = new OrderConfirmedEvent(
            order.getId(), order.getUserId(), order.getEventId());
        rabbitTemplate.convertAndSend(
            RabbitConfig.EXCHANGE,
            RabbitConfig.ORDER_CONFIRMED_ROUTING_KEY,
            event);
        log.info("Published order.confirmed for order {}", orderId);
        ticketsSoldCounter.increment();
      } else if ("DECLINED".equals(status)) {
        order.setStatus(OrderEntity.Status.FAILED);
        orderRepository.save(order);
        throw new PaymentDeclinedException(orderId);
      }

    } catch (HttpServerErrorException ex) {
      log.error("Gateway technical failure for order {}: {}", orderId, ex.getMessage());
      throw ex; // let Retry handle
    }
  }

  // called when circuit breaker is OPEN, gateway is down, fail fast
  public void chargeFallback(Long orderId, String paymentMethod, BigDecimal amount, Throwable ex) {
    // business exceptions are not gateway failures let them propagate as be it
    if (ex instanceof PaymentDeclinedException
        || ex instanceof OrderNotFoundException
        || ex instanceof InvalidOrderStateException) {
      throw (RuntimeException) ex;
    }
    log.error("Circuit breaker fallback for order {}: gateway unavailable. Cause: {}",
        orderId, ex.getMessage());
    throw new GatewayUnavailableException(orderId);
  }
}
