package com.jaka.inventory_service.dto;

import java.math.BigDecimal;

public record InventoryRejected(Long orderId, String productSku, int quantity, String customerName, BigDecimal totalAmount, String reason) {
    
}
