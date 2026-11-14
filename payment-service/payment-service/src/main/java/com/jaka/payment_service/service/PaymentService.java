package com.jaka.payment_service.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jaka.payment_service.dto.InventoryReserved;
import com.jaka.payment_service.dto.PaymentCompleted;
import com.jaka.payment_service.dto.PaymentFailed;
import com.jaka.payment_service.dto.PaymentRefunded;
import com.jaka.payment_service.dto.RefundRequested;
import com.jaka.payment_service.entity.Payment;
import com.jaka.payment_service.entity.PaymentMethod;
import com.jaka.payment_service.entity.PaymentStatus;
import com.jaka.payment_service.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OutboxWriter outboxWriter;

    @Cacheable("payments")
    public List<Payment> getAllPayments() {
        return paymentRepository.findAll();
    }

    // service/PaymentService.java — thêm method
    @Transactional
    @CacheEvict(value = "payments", allEntries = true)
    public void charge(InventoryReserved req) {
        if (paymentRepository.existsByOrderId(req.orderId())) {
            return; // lớp idempotent theo nghiệp vụ: 1 order chỉ thu 1 lần
        }
        // Giả lập cổng thanh toán: từ chối khi số tiền lớn hoặc khách tên "fail"
        boolean approved = req.totalAmount().compareTo(new BigDecimal("1000")) <= 0
                && !"fail".equalsIgnoreCase(req.customerName());

        Payment payment = paymentRepository.save(Payment.builder()
                .paymentReference("PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .orderId(req.orderId())
                .amount(req.totalAmount())
                .currency(req.currency())
                .method(PaymentMethod.CREDIT_CARD)
                .status(approved ? PaymentStatus.COMPLETED : PaymentStatus.FAILED)
                .createdAt(LocalDateTime.now())
                .build());

        if (approved) {
            outboxWriter.write("PaymentCompleted", req.orderId().toString(), new PaymentCompleted(req.orderId(),
                    payment.getPaymentReference(), req.totalAmount(), req.currency(), req.customerName()));
        } else {
            outboxWriter.write("PaymentFailed", req.orderId().toString(), new PaymentFailed(req.orderId(),
                    req.totalAmount(), req.currency(), req.customerName(), "CARD_DECLINED"));
        }
    }

    // Compensation for a payment that arrived after the order was cancelled (timeout).
    // Idempotent on business state: only a COMPLETED payment is refunded, and only once.
    @Transactional
    @CacheEvict(value = "payments", allEntries = true)
    public void refund(RefundRequested req) {
        Payment payment = paymentRepository.findByPaymentReference(req.paymentReference())
                .orElseThrow(() -> new IllegalStateException("No payment " + req.paymentReference()
                        + " for order " + req.orderId())); // retried, then lands in order-events-dlt
        if (payment.getStatus() != PaymentStatus.COMPLETED) {
            return; // already REFUNDED (or never charged) → nothing to do
        }
        payment.setStatus(PaymentStatus.REFUNDED);
        outboxWriter.write("PaymentRefunded", req.orderId().toString(), new PaymentRefunded(req.orderId(),
                payment.getPaymentReference(), payment.getAmount(), payment.getCurrency(), req.customerName(),
                req.reason()));
    }

}
