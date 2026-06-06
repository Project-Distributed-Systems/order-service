package com.ticket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticket.order.application.dto.OrderConfirmedEvent;
import com.ticket.order.application.exceptions.GatewayUnavailableException;
import com.ticket.order.application.exceptions.InvalidOrderStateException;
import com.ticket.order.application.exceptions.OrderNotFoundException;
import com.ticket.order.application.exceptions.PaymentDeclinedException;
import com.ticket.order.infrastructure.messaging.PaymentService;
import com.ticket.order.infrastructure.messaging.RabbitConfig;
import com.ticket.order.infrastructure.persistense.OrderEntity;
import com.ticket.order.infrastructure.persistense.OrderRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
@Import(TestcontainersConfiguration.class)
class PaymentServiceTest {

  // --- mocks ---
  @Mock
  private RestTemplate restTemplate;
  @Mock
  private OrderRepository orderRepository;
  @Mock
  private RabbitTemplate rabbitTemplate;

  // SimpleMeterRegistry é uma implementação in-memory real do Micrometer.
  // Evita a cadeia de stubs frágeis que seriam necessários ao mockar
  // MeterRegistry.
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private PaymentService paymentService;

  private static final Long ORDER_ID = 1L;
  private static final Long USER_ID = 10L;
  private static final Long EVENT_ID = 20L;
  private static final BigDecimal AMOUNT = new BigDecimal("99.90");
  private static final String PAYMENT_METHOD = "CREDIT_CARD";
  private static final String GATEWAY_URL = "http://mock-gateway";

  @BeforeEach
  void setUp() {
    paymentService = new PaymentService(restTemplate, orderRepository, rabbitTemplate, meterRegistry);
    // Injeta o @Value sem precisar de contexto Spring
    ReflectionTestUtils.setField(paymentService, "gatewayUrl", GATEWAY_URL);
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private OrderEntity pendingOrder() {
    return new OrderEntity(USER_ID, EVENT_ID,
        LocalDateTime.now(), LocalDateTime.now().plusMinutes(15));
  }

  private void stubGatewayResponse(String status) {
    when(restTemplate.postForEntity(
        eq(GATEWAY_URL + "/charges"), any(), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("status", status)));
  }

  // =========================================================================
  // Cenário: pagamento aprovado
  // =========================================================================
  @Nested
  @DisplayName("Dado que o gateway aprova o pagamento")
  class WhenPaymentApproved {

    @BeforeEach
    void stubApproved() {
      when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(pendingOrder()));
      stubGatewayResponse("APPROVED");
    }

    @Test
    @DisplayName("deve salvar a order com status CONFIRMED")
    void shouldSaveOrderAsConfirmed() {
      paymentService.processPayment(ORDER_ID, PAYMENT_METHOD, AMOUNT);

      ArgumentCaptor<OrderEntity> captor = ArgumentCaptor.forClass(OrderEntity.class);
      verify(orderRepository).save(captor.capture());
      assertThat(captor.getValue().getStatus()).isEqualTo(OrderEntity.Status.CONFIRMED);
    }

    @Test
    @DisplayName("deve publicar OrderConfirmedEvent na exchange correta com a routing key correta")
    void shouldPublishToCorrectExchangeAndRoutingKey() {
      paymentService.processPayment(ORDER_ID, PAYMENT_METHOD, AMOUNT);

      verify(rabbitTemplate).convertAndSend(
          eq(RabbitConfig.EXCHANGE),
          eq(RabbitConfig.ORDER_CONFIRMED_ROUTING_KEY),
          any(OrderConfirmedEvent.class));
    }

    @Test
    @DisplayName("deve publicar evento com userId e eventId da order")
    void shouldPublishEventWithCorrectFields() {
      paymentService.processPayment(ORDER_ID, PAYMENT_METHOD, AMOUNT);

      ArgumentCaptor<OrderConfirmedEvent> captor = ArgumentCaptor.forClass(OrderConfirmedEvent.class);
      verify(rabbitTemplate).convertAndSend(
          eq(RabbitConfig.EXCHANGE),
          eq(RabbitConfig.ORDER_CONFIRMED_ROUTING_KEY),
          captor.capture());

      OrderConfirmedEvent event = captor.getValue();
      assertThat(event.userId()).isEqualTo(USER_ID);
      assertThat(event.eventId()).isEqualTo(EVENT_ID);
    }

    @Test
    @DisplayName("deve incrementar o contador de tickets vendidos")
    void shouldIncrementTicketsSoldCounter() {
      paymentService.processPayment(ORDER_ID, PAYMENT_METHOD, AMOUNT);

      double count = meterRegistry.counter("tickets_sold_total").count();
      assertThat(count).isEqualTo(1.0);
    }
  }

  // =========================================================================
  // Cenário: pagamento recusado
  // =========================================================================
  @Nested
  @DisplayName("Dado que o gateway recusa o pagamento")
  class WhenPaymentDeclined {

    @BeforeEach
    void stubDeclined() {
      when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(pendingOrder()));
      stubGatewayResponse("DECLINED");
    }

    @Test
    @DisplayName("deve salvar a order com status FAILED")
    void shouldSaveOrderAsFailed() {
      assertThatThrownBy(() -> paymentService.processPayment(ORDER_ID, PAYMENT_METHOD, AMOUNT))
          .isInstanceOf(PaymentDeclinedException.class);

      ArgumentCaptor<OrderEntity> captor = ArgumentCaptor.forClass(OrderEntity.class);
      verify(orderRepository).save(captor.capture());
      assertThat(captor.getValue().getStatus()).isEqualTo(OrderEntity.Status.FAILED);
    }

    @Test
    @DisplayName("nunca deve publicar evento no RabbitMQ")
    void shouldNeverPublishToRabbit() {
      assertThatThrownBy(() -> paymentService.processPayment(ORDER_ID, PAYMENT_METHOD, AMOUNT))
          .isInstanceOf(PaymentDeclinedException.class);

      verify(rabbitTemplate, never()).convertAndSend(
          any(String.class), any(String.class), any(Object.class));
    }
  }

  // =========================================================================
  // Cenário: order não existe
  // =========================================================================
  @Nested
  @DisplayName("Dado que a order não existe no banco")
  class WhenOrderNotFound {

    @Test
    @DisplayName("deve lançar OrderNotFoundException")
    void shouldThrowOrderNotFoundException() {
      when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> paymentService.processPayment(ORDER_ID, PAYMENT_METHOD, AMOUNT))
          .isInstanceOf(OrderNotFoundException.class)
          .hasMessageContaining(ORDER_ID.toString());
    }

    @Test
    @DisplayName("nunca deve chamar o gateway nem o RabbitMQ")
    void shouldNeverCallGatewayOrRabbit() {
      when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> paymentService.processPayment(ORDER_ID, PAYMENT_METHOD, AMOUNT))
          .isInstanceOf(OrderNotFoundException.class);

      verify(restTemplate, never()).postForEntity(any(), any(), any());
      verify(rabbitTemplate, never()).convertAndSend(
          any(String.class), any(String.class), any(Object.class));
    }
  }

  // =========================================================================
  // Cenário: order em estado inválido
  // =========================================================================
  @Nested
  @DisplayName("Dado que a order não está PENDING")
  class WhenOrderNotPending {

    @ParameterizedTest(name = "status = {0}")
    @EnumSource(value = OrderEntity.Status.class, names = { "CONFIRMED", "EXPIRED", "FAILED" })
    @DisplayName("deve lançar InvalidOrderStateException para qualquer status não-PENDING")
    void shouldThrowInvalidOrderStateException(OrderEntity.Status nonPendingStatus) {
      OrderEntity order = pendingOrder();
      order.setStatus(nonPendingStatus);
      when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

      assertThatThrownBy(() -> paymentService.processPayment(ORDER_ID, PAYMENT_METHOD, AMOUNT))
          .isInstanceOf(InvalidOrderStateException.class)
          .hasMessageContaining(nonPendingStatus.name());
    }

    @ParameterizedTest(name = "status = {0}")
    @EnumSource(value = OrderEntity.Status.class, names = { "CONFIRMED", "EXPIRED", "FAILED" })
    @DisplayName("nunca deve publicar no RabbitMQ quando o estado é inválido")
    void shouldNeverPublishToRabbit(OrderEntity.Status nonPendingStatus) {
      OrderEntity order = pendingOrder();
      order.setStatus(nonPendingStatus);
      when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

      assertThatThrownBy(() -> paymentService.processPayment(ORDER_ID, PAYMENT_METHOD, AMOUNT))
          .isInstanceOf(InvalidOrderStateException.class);

      verify(rabbitTemplate, never()).convertAndSend(
          any(String.class), any(String.class), any(Object.class));
    }
  }

  // =========================================================================
  // Cenário: falha técnica no gateway (5xx)
  // =========================================================================
  // @Nested
  // @DisplayName("Dado que o gateway retorna erro técnico 5xx")
  // class WhenGatewayServerError {
  //
  // @Test
  // @DisplayName("deve relançar HttpServerErrorException para o
  // Retry/CircuitBreaker tratar")
  // void shouldRethrowForRetryMechanism() {
  // when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(pendingOrder()));
  // when(restTemplate.postForEntity(any(), any(), eq(Map.class)))
  // .thenThrow(HttpServerErrorException.InternalServerError.class);
  //
  // assertThatThrownBy(() -> paymentService.processPayment(ORDER_ID,
  // PAYMENT_METHOD, AMOUNT))
  // .isInstanceOf(HttpServerErrorException.class);
  // }
  //
  // @Test
  // @DisplayName("nunca deve publicar no RabbitMQ quando há falha técnica")
  // void shouldNeverPublishToRabbit() {
  // when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(pendingOrder()));
  // when(restTemplate.postForEntity(any(), any(), eq(Map.class)))
  // .thenThrow(HttpServerErrorException.InternalServerError.class);
  //
  // assertThatThrownBy(() -> paymentService.processPayment(ORDER_ID,
  // PAYMENT_METHOD, AMOUNT))
  // .isInstanceOf(HttpServerErrorException.class);
  //
  // verify(rabbitTemplate, never()).convertAndSend(
  // any(String.class), any(String.class), any(Object.class));
  // }
  // }
  //
  // =========================================================================
  // Cenário: fallback do CircuitBreaker
  // =========================================================================
  @Nested
  @DisplayName("Dado que o CircuitBreaker está aberto (chargeFallback)")
  class WhenCircuitBreakerFallback {

    @Test
    @DisplayName("deve lançar GatewayUnavailableException para erros genéricos de infraestrutura")
    void shouldThrowGatewayUnavailableException() {
      // O fallback é invocado pelo AOP do Resilience4j em produção.
      // Aqui testamos o método diretamente para garantir sua lógica isolada.
      assertThatThrownBy(() -> paymentService.chargeFallback(ORDER_ID, PAYMENT_METHOD, AMOUNT,
          new RuntimeException("connection refused")))
          .isInstanceOf(GatewayUnavailableException.class);
    }

    @Test
    @DisplayName("deve propagar PaymentDeclinedException sem embrulhar")
    void shouldPropagateBizExceptionPaymentDeclined() {
      PaymentDeclinedException original = new PaymentDeclinedException(ORDER_ID);

      assertThatThrownBy(() -> paymentService.chargeFallback(ORDER_ID, PAYMENT_METHOD, AMOUNT, original))
          .isSameAs(original);
    }

    @Test
    @DisplayName("deve propagar OrderNotFoundException sem embrulhar")
    void shouldPropagateBizExceptionOrderNotFound() {
      OrderNotFoundException original = new OrderNotFoundException(ORDER_ID);

      assertThatThrownBy(() -> paymentService.chargeFallback(ORDER_ID, PAYMENT_METHOD, AMOUNT, original))
          .isSameAs(original);
    }

    @Test
    @DisplayName("deve propagar InvalidOrderStateException sem embrulhar")
    void shouldPropagateBizExceptionInvalidState() {
      InvalidOrderStateException original = new InvalidOrderStateException(ORDER_ID, OrderEntity.Status.CONFIRMED);

      assertThatThrownBy(() -> paymentService.chargeFallback(ORDER_ID, PAYMENT_METHOD, AMOUNT, original))
          .isSameAs(original);
    }
  }
}
