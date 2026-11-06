package com.jaka.inventory_service.dto;

import java.math.BigDecimal;

// Copy of order-service's event contract (fields must match); no shared library between services
public record OrderCreated( Long orderId, String orderNumber, String customerName,
                                BigDecimal totalAmount, String currency, String productSku, int quantity) {
}
