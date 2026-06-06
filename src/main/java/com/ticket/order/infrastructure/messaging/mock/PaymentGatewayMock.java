package com.ticket.order.infrastructure.messaging.mock;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/charges")
public class PaymentGatewayMock {

  @PostMapping
  public ResponseEntity<Map<String, Object>> charge(@RequestBody Map<String, Object> request) {
    // log.info("MOCK GATEWAY: cobrança recebida para order {}",
    // request.get("orderId"));

    // Troque para "DECLINED" para simular recusa
    return ResponseEntity.ok(Map.of("status", "APPROVED"));
  }

  @GetMapping("/healthcheck")
  public ResponseEntity<Map<String, String>> is_alive() {
    return ResponseEntity.ok(Map.of("status", "OK"));
  }
}
