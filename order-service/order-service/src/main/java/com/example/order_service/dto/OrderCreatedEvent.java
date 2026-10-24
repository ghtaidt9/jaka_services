package com.example.order_service.dto;

import com.example.order_service.entity.Order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderCreatedEvent(String eventId, Long orderId, String orderNumber, String customerName,
                                BigDecimal totalAmount, String currency, Instant occurredAt) {

    public static OrderCreatedEvent from(Order order) {
        return new OrderCreatedEvent(UUID.randomUUID().toString(), order.getId(), order.getOrderNumber(),
                order.getCustomerName(), order.getTotalAmount(), order.getCurrency(), Instant.now());
    }
}
