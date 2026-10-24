package com.jaka.payment_service.consumer;

import com.jaka.payment_service.dto.OrderCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OrderEventConsumer {

    @KafkaListener(topics = "order-events", groupId = "payment-service")
    public void onOrderCreated(OrderCreatedEvent event) {
        log.info("Received OrderCreated orderId={} orderNumber={} amount={} {}",
                event.orderId(), event.orderNumber(), event.totalAmount(), event.currency());
    }
}
