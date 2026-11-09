package com.jaka.payment_service.dto;

import java.math.BigDecimal;

public record PaymentRefunded(Long orderId, String paymentReference, BigDecimal amount, String currency,
        String customerName, String reason) {
}
