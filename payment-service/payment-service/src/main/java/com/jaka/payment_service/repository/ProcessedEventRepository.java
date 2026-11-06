package com.jaka.payment_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jaka.payment_service.entity.ProcessedEvent;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {
    
}
