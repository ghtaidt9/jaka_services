package com.jaka.payment_service.consumer;

import java.time.Instant;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.jaka.payment_service.dto.EventEnvelope;
import com.jaka.payment_service.dto.InventoryReserved;
import com.jaka.payment_service.entity.ProcessedEvent;
import com.jaka.payment_service.repository.ProcessedEventRepository;
import com.jaka.payment_service.service.PaymentService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor 
public class InventoryEventConsumer {

    private final ProcessedEventRepository processed;
    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "inventory-events", groupId = "payment-service")
    @Transactional 
    public void onInventoryReserved(EventEnvelope<JsonNode> eventEnvelope) {
        if (processed.existsById(eventEnvelope.eventId())) {
            log.info("Event with id={} has already been processed. Skipping.", eventEnvelope.eventId());
            return;
        }
        if ("InventoryReserved".equals(eventEnvelope.eventType())) {
            paymentService.charge(objectMapper.treeToValue(eventEnvelope.payload(), InventoryReserved.class));
        }
        processed.save(new ProcessedEvent(eventEnvelope.eventId(), Instant.now()));
    }
}
