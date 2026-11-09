package com.jaka.payment_service.consumer;

import java.time.Instant;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.jaka.payment_service.dto.EventEnvelope;
import com.jaka.payment_service.dto.RefundRequested;
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
public class OrderEventConsumer {

    private final ProcessedEventRepository processed;
    private final PaymentService paymentService;
    private final ObjectMapper json;

    // Only RefundRequested matters here; OrderCreated/OrderConfirmed/OrderCancelled are skipped
    @KafkaListener(topics = "order-events", groupId = "payment-service")
    @Transactional
    public void on(EventEnvelope<JsonNode> event) {
        if (processed.existsById(event.eventId())) {
            log.info("Event with id={} has already been processed. Skipping.", event.eventId());
            return;
        }
        if ("RefundRequested".equals(event.eventType())) {
            paymentService.refund(json.treeToValue(event.payload(), RefundRequested.class));
        }
        processed.save(new ProcessedEvent(event.eventId(), Instant.now()));
    }
}
