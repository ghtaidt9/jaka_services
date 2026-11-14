package com.jaka.order_service.dto;

import java.math.BigDecimal;

public record OrderConfirmed(Long orderId, String orderNumber, String customerName, BigDecimal totalAmount,
        String currency) {
}
