package com.ticket.order.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.ticket.order.application.exceptions.InsufficientInventoryException;

@Component
public class EventServiceClient {

  private final RestTemplate restTemplate;
  private final String eventServiceUrl;

  public EventServiceClient(RestTemplate restTemplate,
      @Value("${event-service.url}") String eventServiceUrl) {
    this.restTemplate = restTemplate;
    this.eventServiceUrl = eventServiceUrl;
  }

  public void reserveTicket(Long eventId) {
    try {
      restTemplate.postForEntity(
          eventServiceUrl + "/events/" + eventId + "/reserve",
          null,
          Void.class);
    } catch (HttpClientErrorException ex) {
      if (ex.getStatusCode() == HttpStatus.CONFLICT) {
        throw new InsufficientInventoryException(eventId);
      }
      throw new RuntimeException("Event service error: " + ex.getMessage());
    }
  }
}
