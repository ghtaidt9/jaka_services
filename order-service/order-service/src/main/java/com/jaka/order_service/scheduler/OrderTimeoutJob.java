package com.jaka.order_service.scheduler;

import java.time.LocalDateTime;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.jaka.order_service.dto.OrderCancelled;
import com.jaka.order_service.enums.OrderStatus;
import com.jaka.order_service.repository.OrderRepository;
import com.jaka.order_service.service.OutboxWriter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutJob {

    private final OrderRepository orderRepository;
    private final OutboxWriter writer;

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    @CacheEvict(value = "orders", allEntries = true) // cancelled orders must not stay cached as AWAITING_PAYMENT
    public void expireStuckOrders() {
        orderRepository
                .findByStatusAndUpdatedAtBefore(OrderStatus.AWAITING_PAYMENT, LocalDateTime.now().minusMinutes(5))
                .forEach(o -> {
                    o.cancel("PAYMENT_TIMEOUT");
                    writer.write("OrderCancelled", o.getId().toString(), OrderCancelled.from(o));
                });
    }
}
