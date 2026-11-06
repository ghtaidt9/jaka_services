package com.jaka.order_service.entity;

import java.math.BigDecimal;
import java.time.Instant;

import com.jaka.order_service.enums.SummaryInventoryStatus;
import com.jaka.order_service.enums.SummaryPaymentStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "order_summary")
@Getter
@NoArgsConstructor
public class OrderSummary {
    @Id
    private Long orderId;
    @Version
    private Long version;

    private String orderNumber;
    private String customerName;
    private String productSku;
    private Integer quantity;
    private BigDecimal totalAmount;
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private SummaryInventoryStatus inventoryStatus; // PENDING, RESERVED, REJECTED, RELEASED
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private SummaryPaymentStatus paymentStatus; // PENDING, COMPLETED, FAILED, SKIPPED

    private String paymentReference;
    private String failureReason;
    private Instant createdAt;
    private Instant updatedAt;

    public static OrderSummary from(Order o) {
        var s = new OrderSummary();
        s.orderId = o.getId();
        s.orderNumber = o.getOrderNumber();
        s.customerName = o.getCustomerName();
        s.productSku = o.getProductSku();
        s.quantity = o.getQuantity();
        s.totalAmount = o.getTotalAmount();
        s.currency = o.getCurrency();
        s.inventoryStatus = SummaryInventoryStatus.PENDING;
        s.paymentStatus = SummaryPaymentStatus.PENDING;
        s.createdAt = Instant.now();
        s.updatedAt = s.createdAt;
        return s;
    }

    public void onInventoryReserved() {
        if (inventoryStatus == SummaryInventoryStatus.PENDING) {
            inventoryStatus = SummaryInventoryStatus.RESERVED;
            touch();
        }
    }

    public void onInventoryRejected(String reason) {
        if (inventoryStatus == SummaryInventoryStatus.PENDING) {
            inventoryStatus = SummaryInventoryStatus.REJECTED;
            paymentStatus = SummaryPaymentStatus.SKIPPED; // không giữ được hàng → không bao giờ thu tiền
            failureReason = reason;
            touch();
        }
    }

    public void onInventoryReleased(String reason) {
        if (inventoryStatus != SummaryInventoryStatus.RELEASED) {
            inventoryStatus = SummaryInventoryStatus.RELEASED;
            touch();
        }
    }

    public void onPaymentCompleted(String paymentRef) {
        if (paymentStatus == SummaryPaymentStatus.PENDING) {
            paymentStatus = SummaryPaymentStatus.COMPLETED;
            paymentReference = paymentRef;
            touch();
        }
    }

    public void onPaymentFailed(String reason) {
        if (paymentStatus == SummaryPaymentStatus.PENDING) {
            paymentStatus = SummaryPaymentStatus.FAILED;
            failureReason = reason;
            touch();
        }
    }

    private void touch() {
        updatedAt = Instant.now();
    }
}
