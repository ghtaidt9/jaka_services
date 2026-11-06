package com.jaka.order_service.consumer;

import java.time.Instant;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.jaka.order_service.dto.EventEnvelope;
import com.jaka.order_service.dto.InventoryRejected;
import com.jaka.order_service.dto.InventoryReleased;
import com.jaka.order_service.dto.PaymentCompleted;
import com.jaka.order_service.dto.PaymentFailed;
import com.jaka.order_service.entity.OrderSummary;
import com.jaka.order_service.entity.ProjectedEvent;
import com.jaka.order_service.exception.OrderSummaryNotFoundException;
import com.jaka.order_service.repository.OrderSummaryRepository;
import com.jaka.order_service.repository.ProjectedEventRepository;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderSummaryProjector {
    private final OrderSummaryRepository summaryRepository;
    private final ProjectedEventRepository projected;
    private final ObjectMapper json;

    @KafkaListener(topics = { "inventory-events",
            "payment-events" }, groupId = "order-summary-projector", autoStartup = "${app.projector.enabled:true}")
    @Transactional
    public void on(EventEnvelope<JsonNode> event) {
        if (projected.existsById(event.eventId())) {
            return;
        }
        Long orderId = Long.valueOf(event.aggregateId());
        OrderSummary s = summaryRepository.findById(orderId)
                .orElseThrow(() -> new OrderSummaryNotFoundException(orderId));
        switch (event.eventType()) {
            case "InventoryReserved" -> s.onInventoryReserved();
            case "InventoryRejected" -> s.onInventoryRejected(
                    json.treeToValue(event.payload(), InventoryRejected.class).reason());
            case "InventoryReleased" -> s.onInventoryReleased(
                    json.treeToValue(event.payload(), InventoryReleased.class).reason());
            case "PaymentCompleted" -> s.onPaymentCompleted(
                    json.treeToValue(event.payload(), PaymentCompleted.class).paymentReference());
            case "PaymentFailed" -> s.onPaymentFailed(
                    json.treeToValue(event.payload(), PaymentFailed.class).reason());
            default -> log.debug("Ignoring {}", event.eventType());
        }
        projected.save(new ProjectedEvent(event.eventId(), Instant.now()));
        log.info("Projected {} -> order {} [{} / {}]", event.eventType(), orderId, s.getInventoryStatus(),
                s.getPaymentStatus());
    }
}
