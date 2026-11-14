package com.jaka.order_service.repository;

import com.jaka.order_service.entity.Order;
import com.jaka.order_service.enums.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
    public List<Order> findByStatusAndUpdatedAtBefore(OrderStatus status, LocalDateTime before);
}
