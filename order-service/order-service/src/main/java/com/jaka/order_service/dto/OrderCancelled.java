package com.jaka.order_service.dto;

import com.jaka.order_service.entity.Order;

public record OrderCancelled(Long orderId, String orderNumber, String customerName, String productSku, int quantity, String reason) {
    public static OrderCancelled from(Order order) {
        return new OrderCancelled(order.getId(), order.getOrderNumber(), order.getCustomerName(), order.getProductSku(), order.getQuantity(), order.getCancelReason());
    }
}
