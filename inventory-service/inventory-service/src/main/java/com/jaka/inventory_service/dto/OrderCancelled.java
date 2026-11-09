package com.jaka.inventory_service.dto;

// Copy of order-service's OrderCancelled (no shared library between services)
public record OrderCancelled(Long orderId, String orderNumber, String customerName, String productSku, int quantity,
        String reason) {
}
