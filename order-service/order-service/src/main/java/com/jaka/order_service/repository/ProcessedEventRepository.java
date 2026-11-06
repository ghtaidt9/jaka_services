package com.jaka.order_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jaka.order_service.entity.ProcessedEvent;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {
    
}
