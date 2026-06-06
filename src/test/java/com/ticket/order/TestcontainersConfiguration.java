package com.ticket.order;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

  /**
   * Container do PostgreSQL.
   *
   * @ServiceConnection detecta automaticamente o tipo do container e sobrescreve:
   *                    spring.datasource.url, .username e .password
   *                    sem precisar de @DynamicPropertySource.
   *
   *                    alpine = imagem enxuta (~50 MB vs ~150 MB da oficial).
   */
  @Bean
  @ServiceConnection
  PostgreSQLContainer postgresContainer() {
    return new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("test_db")
        .withUsername("test")
        .withPassword("test");
  }

  /**
   * Container do RabbitMQ.
   *
   * @ServiceConnection sobrescreve:
   *                    spring.rabbitmq.host, .port, .username e .password
   *
   *                    A tag -management inclui o painel web na porta 15672,
   *                    útil para inspecionar filas durante debug dos testes de
   *                    integração.
   */
  @Bean
  @ServiceConnection
  RabbitMQContainer rabbitMQContainer() {
    return new RabbitMQContainer("rabbitmq:3.13-management-alpine");
  }
}
