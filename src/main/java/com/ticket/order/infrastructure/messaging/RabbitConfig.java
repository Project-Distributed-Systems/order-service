package com.ticket.order.infrastructure.messaging;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;

import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

  public static final String EXCHANGE = "tickets.exchange";
  public static final String ORDER_CONFIRMED_QUEUE = "order.confirmed.queue";
  public static final String ORDER_CONFIRMED_ROUTING_KEY = "order.confirmed";

  public static final String DLX = "tickets.dlx";
  public static final String DLQ = "order.confirmed.dlq";

  @Bean
  public TopicExchange ticketsExchange() {
    return new TopicExchange(EXCHANGE);
  }

  @Bean
  public Queue orderConfirmedQueue() {
    return QueueBuilder.durable(ORDER_CONFIRMED_QUEUE)
        .withArgument("x-dead-letter-exchange", DLX)
        .withArgument("x-dead-letter-routing-key", DLQ)
        .build();
  }

  @Bean
  public Binding orderConfirmedBinding(Queue orderConfirmedQueue, TopicExchange ticketsExchange) {
    return BindingBuilder.bind(orderConfirmedQueue)
        .to(ticketsExchange)
        .with(ORDER_CONFIRMED_ROUTING_KEY);
  }

  // JSON serialization instead of Java serialization
  @Bean
  public MessageConverter jsonMessageConverter() {
    return new JacksonJsonMessageConverter();
  }

  @Bean
  public RabbitTemplate rabbitTemplate(ConnectionFactory cf, MessageConverter converter) {
    RabbitTemplate template = new RabbitTemplate(cf);
    template.setMessageConverter(converter);
    return template;
  }
}
