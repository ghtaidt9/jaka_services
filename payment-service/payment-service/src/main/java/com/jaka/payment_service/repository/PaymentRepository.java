package com.jaka.payment_service.repository;

import java.util.Optional;

import com.jaka.payment_service.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    boolean existsByOrderId(Long orderId);

    Optional<Payment> findByPaymentReference(String paymentReference);
}
