package com.jaka.order_service.entity;

import java.math.BigDecimal;
import java.time.Instant;

import com.jaka.order_service.enums.SummaryInventoryStatus;
import com.jaka.order_service.enums.SummaryPaymentStatus;

public record OrderSummaryResponse(Long orderId, String orderNumber, String customerName,
        String productSku, Integer quantity, BigDecimal totalAmount, String currency,
        String inventoryStatus, String paymentStatus, String paymentReference,
        String failureReason, String overallStatus, Instant updatedAt) {

    public static OrderSummaryResponse from(OrderSummary s) {
        return new OrderSummaryResponse(s.getOrderId(), s.getOrderNumber(), s.getCustomerName(),
                s.getProductSku(), s.getQuantity(), s.getTotalAmount(), s.getCurrency(),
                s.getInventoryStatus().name(), s.getPaymentStatus().name(), s.getPaymentReference(),
                s.getFailureReason(), overall(s), s.getUpdatedAt());
    }

    // 1 trạng thái dễ hiểu cho màn hình, tính lúc đọc — không lưu
    private static String overall(OrderSummary s) {
        if (s.getInventoryStatus() == SummaryInventoryStatus.REJECTED)
            return "OUT_OF_STOCK";
        if (s.getPaymentStatus() == SummaryPaymentStatus.FAILED)
            return "PAYMENT_FAILED";
        if (s.getPaymentStatus() == SummaryPaymentStatus.COMPLETED)
            return "COMPLETED";
        return "PROCESSING";
    }
}
