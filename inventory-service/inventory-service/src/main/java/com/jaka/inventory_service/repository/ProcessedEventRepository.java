package com.jaka.inventory_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jaka.inventory_service.entity.ProcessedEvent;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

}
