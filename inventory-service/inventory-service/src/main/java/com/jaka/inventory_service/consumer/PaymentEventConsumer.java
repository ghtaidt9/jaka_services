package com.jaka.inventory_service.consumer;

import java.time.Instant;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.jaka.inventory_service.dto.EventEnvelope;
import com.jaka.inventory_service.dto.PaymentCompleted;
import com.jaka.inventory_service.dto.PaymentFailed;
import com.jaka.inventory_service.entity.ProcessedEvent;
import com.jaka.inventory_service.repository.ProcessedEventRepository;
import com.jaka.inventory_service.service.InventoryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j 
@Component 
@RequiredArgsConstructor 
public class PaymentEventConsumer {

    private final ProcessedEventRepository processed;
    private final InventoryService inventoryService;
    private final ObjectMapper json;

    @KafkaListener (topics = "payment-events", groupId = "inventory-service")
    @Transactional
    public void on(EventEnvelope<JsonNode> event) {
        if ((processed.existsById(event.eventId()))) {
            log.info("Event has been processed {}", event.eventId());
            return;
        }
        switch (event.eventType()) {
            case "PaymentCompleted" -> inventoryService.commit(json.treeToValue(event.payload(), PaymentCompleted.class));
            case "PaymentFailed" -> inventoryService.release(json.treeToValue(event.payload(), PaymentFailed.class));
            default -> log.debug("Ignoring {} for order {}", event.eventType(), event.aggregateId()); // e.g. PaymentRefunded
        }
        processed.save(new ProcessedEvent(event.eventId(), Instant.now()));
    }
    
}
