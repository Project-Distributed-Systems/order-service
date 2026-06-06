package com.ticket.order.infrastructure.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.ticket.order.application.OrderService;
import com.ticket.order.application.dto.CreateOrderRequest;
import com.ticket.order.infrastructure.messaging.PaymentService;
import com.ticket.order.infrastructure.persistense.OrderEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/orders")
public class OrderController {

  private final OrderService service;
  private final PaymentService paymentService;

  public OrderController(OrderService service, PaymentService paymentService) {
    this.service = service;
    this.paymentService = paymentService;
  }

  @PostMapping
  public ResponseEntity<OrderEntity> create(@RequestBody CreateOrderRequest req) {
    OrderEntity order = service.createOrder(req.userId(), req.eventId());
    return ResponseEntity.status(HttpStatus.CREATED).body(order);
  }

  @GetMapping("/{id}")
  public OrderEntity findById(@PathVariable Long id) {
    return service.findById(id);
  }

  @GetMapping
  public List<OrderEntity> findAll() {
    return service.findAll();
  }

  @PostMapping("/{id}/pay")
  public ResponseEntity<Map<String, String>> pay(
      @PathVariable Long id,
      @RequestBody Map<String, Object> body) {

    String method = (String) body.get("paymentMethod");
    BigDecimal amount = new BigDecimal(body.get("amount").toString());
    paymentService.processPayment(id, method, amount);
    return ResponseEntity.ok(Map.of("message", "Payment processed"));
  }
}
