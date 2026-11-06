package com.jaka.order_service.dto;

public record InventoryReleased(Long orderId, String productSku, int quantity, String customerName, String reason) {
}
