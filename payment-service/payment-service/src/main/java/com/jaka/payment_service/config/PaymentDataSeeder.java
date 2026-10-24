package com.jaka.payment_service.config;

import com.jaka.payment_service.entity.Payment;
import com.jaka.payment_service.entity.PaymentMethod;
import com.jaka.payment_service.entity.PaymentStatus;
import com.jaka.payment_service.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class PaymentDataSeeder implements CommandLineRunner {

    private final PaymentRepository paymentRepository;

    // orderId values 1..8 assume a fresh order-service seed (see OrderDataSeeder) with
    // matching IDENTITY-generated IDs. This is a best-effort convention, not an enforced
    // cross-service reference, since order-service and payment-service own separate databases.
    @Override
    public void run(String... args) {
        if (paymentRepository.count() > 0) {
            return;
        }

        paymentRepository.saveAll(List.of(
                Payment.builder().paymentReference("PAY-2001").orderId(1L)
                        .amount(new BigDecimal("129.99")).currency("USD")
                        .method(PaymentMethod.CREDIT_CARD).status(PaymentStatus.COMPLETED)
                        .createdAt(LocalDateTime.now().minusDays(10)).build(),
                Payment.builder().paymentReference("PAY-2002").orderId(2L)
                        .amount(new BigDecimal("59.50")).currency("USD")
                        .method(PaymentMethod.PAYPAL).status(PaymentStatus.COMPLETED)
                        .createdAt(LocalDateTime.now().minusDays(8)).build(),
                Payment.builder().paymentReference("PAY-2003").orderId(3L)
                        .amount(new BigDecimal("210.00")).currency("USD")
                        .method(PaymentMethod.BANK_TRANSFER).status(PaymentStatus.COMPLETED)
                        .createdAt(LocalDateTime.now().minusDays(6)).build(),
                Payment.builder().paymentReference("PAY-2004").orderId(4L)
                        .amount(new BigDecimal("15.75")).currency("USD")
                        .method(PaymentMethod.WALLET).status(PaymentStatus.PENDING)
                        .createdAt(LocalDateTime.now().minusDays(5)).build(),
                Payment.builder().paymentReference("PAY-2005").orderId(5L)
                        .amount(new BigDecimal("300.00")).currency("USD")
                        .method(PaymentMethod.CREDIT_CARD).status(PaymentStatus.REFUNDED)
                        .createdAt(LocalDateTime.now().minusDays(4)).build(),
                Payment.builder().paymentReference("PAY-2006").orderId(6L)
                        .amount(new BigDecimal("89.20")).currency("USD")
                        .method(PaymentMethod.DEBIT_CARD).status(PaymentStatus.COMPLETED)
                        .createdAt(LocalDateTime.now().minusDays(3)).build(),
                Payment.builder().paymentReference("PAY-2007").orderId(7L)
                        .amount(new BigDecimal("45.00")).currency("USD")
                        .method(PaymentMethod.PAYPAL).status(PaymentStatus.COMPLETED)
                        .createdAt(LocalDateTime.now().minusDays(2)).build(),
                Payment.builder().paymentReference("PAY-2008").orderId(8L)
                        .amount(new BigDecimal("175.30")).currency("USD")
                        .method(PaymentMethod.CREDIT_CARD).status(PaymentStatus.FAILED)
                        .createdAt(LocalDateTime.now().minusDays(1)).build()
        ));
    }
}
