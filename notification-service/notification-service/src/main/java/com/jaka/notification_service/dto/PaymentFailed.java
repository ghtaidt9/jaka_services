package com.jaka.notification_service.dto;

import java.math.BigDecimal;

public record PaymentFailed(Long orderId, BigDecimal amount, String currency, String customerName, String reason) {
    
}
