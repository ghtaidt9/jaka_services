package com.example.order_service.dto;

import java.math.BigDecimal;

public record CreateOrderRequest(String customerName, BigDecimal totalAmount, String currency) {
}
