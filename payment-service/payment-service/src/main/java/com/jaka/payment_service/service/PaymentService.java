package com.jaka.payment_service.service;

import com.jaka.payment_service.entity.Payment;
import com.jaka.payment_service.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;

    @Cacheable("payments")
    public List<Payment> getAllPayments() {
        return paymentRepository.findAll();
    }
}
