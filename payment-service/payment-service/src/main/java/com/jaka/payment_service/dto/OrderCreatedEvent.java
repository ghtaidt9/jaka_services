package com.jaka.payment_service.dto;

import java.math.BigDecimal;
import java.time.Instant;

// Copy of order-service's event contract (fields must match); no shared library between services
public record OrderCreatedEvent(String eventId, Long orderId, String orderNumber, String customerName,
                                BigDecimal totalAmount, String currency, Instant occurredAt) {
}
