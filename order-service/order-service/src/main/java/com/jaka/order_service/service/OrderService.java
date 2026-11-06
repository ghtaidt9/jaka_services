package com.jaka.order_service.service;

import java.util.List;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jaka.order_service.dto.CreateOrderRequest;
import com.jaka.order_service.dto.OrderCreated;
import com.jaka.order_service.entity.Order;
import com.jaka.order_service.entity.OrderSummary;
import com.jaka.order_service.repository.OrderRepository;
import com.jaka.order_service.repository.OrderSummaryRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderSummaryRepository orderSummaryRepository;
    private final OutboxWriter writer;

    @Cacheable("orders")
    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }

    @Transactional
    @CacheEvict(value = "orders", allEntries = true)
    public Order create(CreateOrderRequest req) {
        Order order = orderRepository.save(Order.pending(req));
        orderSummaryRepository.save(OrderSummary.from(order));
        writer.write("OrderCreated", order.getId().toString(), OrderCreated.from(order));
        return order;
    }
}
