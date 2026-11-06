package com.jaka.order_service.dto;

import java.math.BigDecimal;

public record PaymentCompleted(Long orderId, String paymentReference, BigDecimal amount, String currency,
        String customerName) {

}
