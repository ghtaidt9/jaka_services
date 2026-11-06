package com.jaka.order_service.exception;

public class OrderSummaryNotFoundException extends RuntimeException {
    public OrderSummaryNotFoundException(Long orderId) { super("No order_summary for order " + orderId); }
}
