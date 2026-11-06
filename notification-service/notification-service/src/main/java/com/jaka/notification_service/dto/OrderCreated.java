package com.jaka.notification_service.dto;

import java.math.BigDecimal;

public record OrderCreated(Long orderId, String orderNumber, String customerName,
        BigDecimal totalAmount, String currency, String productSku, int quantity) {
}
