package com.jaka.order_service.dto;

import com.jaka.order_service.entity.Order;

import java.math.BigDecimal;

public record OrderCreated(Long orderId, String orderNumber, String customerName,
                                BigDecimal totalAmount, String currency, String productSku, int quantity) {

    public static OrderCreated from(Order order) {
        return new OrderCreated(order.getId(), order.getOrderNumber(),
                order.getCustomerName(), order.getTotalAmount(), order.getCurrency(), order.getProductSku(), order.getQuantity());
    }
}
