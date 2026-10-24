package com.example.order_service.config;

import com.example.order_service.entity.Order;
import com.example.order_service.entity.OrderStatus;
import com.example.order_service.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class OrderDataSeeder implements CommandLineRunner {

    private final OrderRepository orderRepository;

    @Override
    public void run(String... args) {
        if (orderRepository.count() > 0) {
            return;
        }

        orderRepository.saveAll(List.of(
                Order.builder().orderNumber("ORD-1001").customerName("Alice Nguyen")
                        .status(OrderStatus.DELIVERED).totalAmount(new BigDecimal("129.99")).currency("USD")
                        .createdAt(LocalDateTime.now().minusDays(10)).build(),
                Order.builder().orderNumber("ORD-1002").customerName("Bao Tran")
                        .status(OrderStatus.SHIPPED).totalAmount(new BigDecimal("59.50")).currency("USD")
                        .createdAt(LocalDateTime.now().minusDays(8)).build(),
                Order.builder().orderNumber("ORD-1003").customerName("Carla Reyes")
                        .status(OrderStatus.PAID).totalAmount(new BigDecimal("210.00")).currency("USD")
                        .createdAt(LocalDateTime.now().minusDays(6)).build(),
                Order.builder().orderNumber("ORD-1004").customerName("Daniel Kim")
                        .status(OrderStatus.CREATED).totalAmount(new BigDecimal("15.75")).currency("USD")
                        .createdAt(LocalDateTime.now().minusDays(5)).build(),
                Order.builder().orderNumber("ORD-1005").customerName("Emma Johnson")
                        .status(OrderStatus.CANCELLED).totalAmount(new BigDecimal("300.00")).currency("USD")
                        .createdAt(LocalDateTime.now().minusDays(4)).build(),
                Order.builder().orderNumber("ORD-1006").customerName("Farid Haidari")
                        .status(OrderStatus.DELIVERED).totalAmount(new BigDecimal("89.20")).currency("USD")
                        .createdAt(LocalDateTime.now().minusDays(3)).build(),
                Order.builder().orderNumber("ORD-1007").customerName("Giang Le")
                        .status(OrderStatus.PAID).totalAmount(new BigDecimal("45.00")).currency("USD")
                        .createdAt(LocalDateTime.now().minusDays(2)).build(),
                Order.builder().orderNumber("ORD-1008").customerName("Hana Sato")
                        .status(OrderStatus.SHIPPED).totalAmount(new BigDecimal("175.30")).currency("USD")
                        .createdAt(LocalDateTime.now().minusDays(1)).build()
        ));
    }
}
