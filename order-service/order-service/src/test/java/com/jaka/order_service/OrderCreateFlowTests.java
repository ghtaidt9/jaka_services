package com.jaka.order_service;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.jaka.order_service.dto.CreateOrderRequest;
import com.jaka.order_service.repository.OutboxRepository;
import com.jaka.order_service.service.OrderService;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
public class OrderCreateFlowTests {

    @Autowired
    OrderService orderService;

    @Autowired
    OutboxRepository outboxRepository;

    @Test
    void createOrder_writeOutboxEvent() {
        var order = orderService.create(new CreateOrderRequest("alice", new BigDecimal("10.00"), "USD", "TEST-001", 1));
        assertThat(outboxRepository.findAll()).anySatisfy(event -> {
            assertThat(event.getEventType()).isEqualTo("OrderCreated");
            assertThat(event.getAggregateId()).isEqualTo(order.getId().toString());
        });
    }

}
