package com.jaka.inventory_service.dto;

public record InventoryReleased(Long orderId, String productSku, int quantity, String customerName, String reason) {
}
