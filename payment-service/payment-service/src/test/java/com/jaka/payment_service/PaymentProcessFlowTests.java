package com.jaka.payment_service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.jaka.payment_service.dto.InventoryReserved;
import com.jaka.payment_service.entity.PaymentStatus;
import com.jaka.payment_service.repository.OutboxRepository;
import com.jaka.payment_service.repository.PaymentRepository;
import com.jaka.payment_service.service.PaymentService;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
public class PaymentProcessFlowTests {

    @Autowired 
    PaymentRepository paymentRepository;

    @Autowired 
    PaymentService paymentService;

    @Autowired 
    OutboxRepository outboxRepository;

    @Test 
    void chargeSuccessTest() {
        Long orderId = 900002L;
        paymentService.charge(new InventoryReserved(orderId, "TEST-001", 10, "Alice", new BigDecimal("10.00"), "USD"));
        // assertThat(paymentRepository.findAll().stream().anyMatch(payment -> payment.getOrderId() == orderId && payment.getStatus() == PaymentStatus.COMPLETED)).isTrue();
        // assertThat(outboxRepository.findAll().stream().anyMatch(oubox -> oubox.getAggregateId().equals(orderId.toString()) && oubox.getEventType().equals("PaymentCompleted"))).isTrue();
        assertThat(paymentRepository.findAll()).anySatisfy(event -> {
            assertThat(event.getOrderId()).isEqualTo(orderId);
            assertThat(event.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        });
    }

}
