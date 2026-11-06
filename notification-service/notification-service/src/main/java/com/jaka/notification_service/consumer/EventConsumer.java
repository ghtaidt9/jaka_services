package com.jaka.notification_service.consumer;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.jaka.notification_service.dto.EventEnvelope;
import com.jaka.notification_service.service.NotificationService;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

@Component 
@RequiredArgsConstructor 
public class EventConsumer {
    
    private final NotificationService notificationService;
    @KafkaListener (topics = {"order-events", "payment-events", "inventory-events"}, groupId = "notification-service")
    public void on(EventEnvelope<JsonNode> event) {
        notificationService.handle(event);
    }
    
}
