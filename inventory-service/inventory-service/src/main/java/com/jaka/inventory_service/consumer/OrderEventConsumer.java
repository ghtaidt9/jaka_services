package com.jaka.inventory_service.consumer;

import java.time.Instant;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.jaka.inventory_service.dto.EventEnvelope;
import com.jaka.inventory_service.dto.OrderCancelled;
import com.jaka.inventory_service.dto.OrderCreated;
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
public class OrderEventConsumer {

    private final ProcessedEventRepository processed;
    private final InventoryService inventoryService;
    private final ObjectMapper json;

    // order-events carries OrderCreated, OrderConfirmed, OrderCancelled, RefundRequested:
    // dispatch on eventType instead of assuming every payload is an OrderCreated
    @KafkaListener(topics = "order-events", groupId = "inventory-service")
    @Transactional
    public void on(EventEnvelope<JsonNode> event) {
        if (processed.existsById(event.eventId())) {
            log.info("Event has been processed {}", event.eventId());
            return;
        }
        switch (event.eventType()) {
            case "OrderCreated" -> inventoryService.reserve(json.treeToValue(event.payload(), OrderCreated.class));
            case "OrderCancelled" -> inventoryService.releaseForCancelledOrder(
                    json.treeToValue(event.payload(), OrderCancelled.class));
            default -> log.debug("Ignoring {} for order {}", event.eventType(), event.aggregateId());
        }
        processed.save(new ProcessedEvent(event.eventId(), Instant.now()));
    }
}
