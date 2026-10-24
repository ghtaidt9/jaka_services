package com.example.order_service.service;

import com.example.order_service.dto.CreateOrderRequest;
import com.example.order_service.dto.OrderCreatedEvent;
import com.example.order_service.entity.Order;
import com.example.order_service.entity.OrderStatus;
import com.example.order_service.publisher.OrderEventPublisher;
import com.example.order_service.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderEventPublisher publisher;

    @Cacheable("orders")
    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }

    @Transactional
    @CacheEvict(value = "orders", allEntries = true)
    public Order create(CreateOrderRequest req) {
        Order order = orderRepository.save(Order.builder()
                .orderNumber("ORD-" + System.currentTimeMillis())
                .customerName(req.customerName())
                .status(OrderStatus.CREATED)
                .totalAmount(req.totalAmount())
                .currency(req.currency())
                .createdAt(LocalDateTime.now())
                .build());
        // Week 9 replaces this direct send with the Outbox pattern
        publisher.publish(OrderCreatedEvent.from(order));
        return order;
    }
}
