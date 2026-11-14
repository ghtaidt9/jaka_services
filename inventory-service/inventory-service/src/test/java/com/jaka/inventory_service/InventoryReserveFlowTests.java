package com.jaka.inventory_service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.jaka.inventory_service.dto.OrderCreated;
import com.jaka.inventory_service.dto.PaymentFailed;
import com.jaka.inventory_service.entity.Product;
import com.jaka.inventory_service.entity.ReservationStatus;
import com.jaka.inventory_service.repository.OutboxRepository;
import com.jaka.inventory_service.repository.ProductRepository;
import com.jaka.inventory_service.repository.ReservationRepository;
import com.jaka.inventory_service.service.InventoryService;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class InventoryReserveFlowTests {

    @Autowired
    InventoryService inventoryService;

    @Autowired
    ProductRepository productRepository;

    @Autowired
    ReservationRepository reservationRepository;

    @Autowired
    OutboxRepository outboxRepository;

    @Test
    void reserve_inStock_holdsStockAndWritesInventoryReserved() {
        Long orderId = 910001L;
        Product before = productRepository.findById("TEST-001").orElseThrow();

        inventoryService.reserve(orderCreated(orderId, "TEST-001", 2));

        Product after = productRepository.findById("TEST-001").orElseThrow();
        assertThat(after.getAvailable()).isEqualTo(before.getAvailable() - 2);
        assertThat(after.getReserved()).isEqualTo(before.getReserved() + 2);

        assertThat(reservationRepository.findById(orderId)).hasValueSatisfying(r -> {
            assertThat(r.getSku()).isEqualTo("TEST-001");
            assertThat(r.getQuantity()).isEqualTo(2);
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        });

        assertThat(outboxRepository.findAll()).anySatisfy(e -> {
            assertThat(e.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(e.getEventType()).isEqualTo("InventoryReserved");
        });
    }

    @Test
    void reserve_soldOut_rejectsWithoutReservation() {
        Long orderId = 910002L;

        inventoryService.reserve(orderCreated(orderId, "SOLD-OUT-001", 1));

        assertThat(reservationRepository.findById(orderId)).isEmpty();
        assertThat(outboxRepository.findAll()).anySatisfy(e -> {
            assertThat(e.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(e.getEventType()).isEqualTo("InventoryRejected");
        });
    }

    @Test
    void release_afterPaymentFailed_returnsStock() {
        Long orderId = 910003L;
        Product before = productRepository.findById("TEST-001").orElseThrow();

        inventoryService.reserve(orderCreated(orderId, "TEST-001", 3));
        inventoryService.release(new PaymentFailed(orderId, new BigDecimal("30.00"), "USD", "alice", "CARD_DECLINED"));

        Product after = productRepository.findById("TEST-001").orElseThrow();
        assertThat(after.getAvailable()).isEqualTo(before.getAvailable());
        assertThat(after.getReserved()).isEqualTo(before.getReserved());

        assertThat(reservationRepository.findById(orderId))
                .hasValueSatisfying(r -> assertThat(r.getStatus()).isEqualTo(ReservationStatus.RELEASED));
        assertThat(outboxRepository.findAll()).anySatisfy(e -> {
            assertThat(e.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(e.getEventType()).isEqualTo("InventoryReleased");
        });
    }

    private static OrderCreated orderCreated(Long orderId, String sku, int quantity) {
        return new OrderCreated(orderId, "ORD-" + orderId, "alice", new BigDecimal("10.00"), "USD", sku, quantity);
    }
}
