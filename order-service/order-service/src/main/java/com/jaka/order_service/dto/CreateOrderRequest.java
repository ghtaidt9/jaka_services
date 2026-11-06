package com.jaka.order_service.dto;

import java.math.BigDecimal;

public record CreateOrderRequest(String customerName, BigDecimal totalAmount, String currency, String productSku, int quantity) {
}
