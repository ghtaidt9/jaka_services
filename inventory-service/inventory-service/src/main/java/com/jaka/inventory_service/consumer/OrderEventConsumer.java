package com.jaka.inventory_service.consumer;

import java.time.Instant;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.jaka.inventory_service.dto.EventEnvelope;
import com.jaka.inventory_service.dto.OrderCreated;
import com.jaka.inventory_service.entity.ProcessedEvent;
import com.jaka.inventory_service.repository.ProcessedEventRepository;
import com.jaka.inventory_service.service.InventoryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j 
@Component 
@RequiredArgsConstructor 
public class OrderEventConsumer {
    
    private final ProcessedEventRepository processed;
    private final InventoryService inventoryService;

    @KafkaListener(topics = "order-events", groupId = "inventory-service")
    @Transactional
    public void on(EventEnvelope<OrderCreated> event) {
        if (processed.existsById(event.eventId())) {
            log.info("Event has been processed {}", event.eventId());
            return;
        }
        inventoryService.reserve(event.payload());
        processed.save(new ProcessedEvent(event.eventId(), Instant.now()));
    }
}