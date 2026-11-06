package com.jaka.payment_service.dto;

import java.math.BigDecimal;

public record InventoryReserved(Long orderId, String productSku, int quantity, String customerName, BigDecimal totalAmount, String currency) {
}
