package com.jaka.order_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jaka.order_service.entity.OrderSummary;

public interface OrderSummaryRepository extends JpaRepository<OrderSummary, Long> {}