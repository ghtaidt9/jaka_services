package com.example.order_service.publisher;

import com.example.order_service.dto.OrderCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderEventPublisher {

    private static final String TOPIC = "order-events";

    private final KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate;

    public void publish(OrderCreatedEvent event) {
        // key = orderId -> every event of the same order lands in the same partition, keeping order
        kafkaTemplate.send(TOPIC, event.orderId().toString(), event);
    }
}
