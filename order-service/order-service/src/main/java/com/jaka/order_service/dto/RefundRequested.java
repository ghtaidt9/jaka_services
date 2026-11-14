package com.jaka.order_service.dto;

import java.math.BigDecimal;

public record RefundRequested(Long orderId, String paymentReference, BigDecimal amount, String currency,
        String customerName, String reason) {

}
