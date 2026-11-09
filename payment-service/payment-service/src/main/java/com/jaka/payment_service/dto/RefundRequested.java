package com.jaka.payment_service.dto;

import java.math.BigDecimal;

// Copy of order-service's RefundRequested (no shared library between services)
public record RefundRequested(Long orderId, String paymentReference, BigDecimal amount, String currency,
        String customerName, String reason) {
}
