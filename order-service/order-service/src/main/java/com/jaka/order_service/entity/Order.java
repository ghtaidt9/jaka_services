package com.jaka.order_service.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.jaka.order_service.dto.CreateOrderRequest;
import com.jaka.order_service.enums.OrderStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String orderNumber;

    @Column(nullable = false, length = 120)
    private String customerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private String productSku;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column(length = 50)
    private String cancelReason;

    @Version
    private Long version;

    public static Order pending(CreateOrderRequest req) {
        return Order.builder()
                .orderNumber("ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .customerName(req.customerName())
                .status(OrderStatus.PENDING)
                .totalAmount(req.totalAmount())
                .currency(req.currency())
                .productSku(req.productSku())
                .quantity(req.quantity())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    public void awaitPayment() {
        if (getStatus().equals(OrderStatus.PENDING)) {
            setStatus(OrderStatus.AWAITING_PAYMENT);
            touch();
        }
    }

    public void confirm() {
        if (getStatus().equals(OrderStatus.AWAITING_PAYMENT)) {
            setStatus(OrderStatus.CONFIRMED);
            touch();
        }
    }

    public void cancel(String reason) {
        if (getStatus().equals(OrderStatus.PENDING) || getStatus().equals(OrderStatus.AWAITING_PAYMENT)) {
            setStatus(OrderStatus.CANCELLED);
            setCancelReason(reason);
            touch();
        }
    }

    private void touch() {
        setUpdatedAt(LocalDateTime.now());
    }
}
