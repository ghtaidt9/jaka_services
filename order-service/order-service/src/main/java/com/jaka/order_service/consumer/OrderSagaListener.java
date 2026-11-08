package com.jaka.order_service.consumer;

import java.time.Instant;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.jaka.order_service.dto.EventEnvelope;
import com.jaka.order_service.dto.InventoryRejected;
import com.jaka.order_service.dto.OrderCancelled;
import com.jaka.order_service.dto.OrderConfirmed;
import com.jaka.order_service.dto.PaymentCompleted;
import com.jaka.order_service.dto.PaymentFailed;
import com.jaka.order_service.dto.RefundRequested;
import com.jaka.order_service.entity.Order;
import com.jaka.order_service.entity.ProcessedEvent;
import com.jaka.order_service.enums.OrderStatus;
import com.jaka.order_service.repository.OrderRepository;
import com.jaka.order_service.repository.ProcessedEventRepository;
import com.jaka.order_service.service.OutboxWriter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderSagaListener {

    private final OrderRepository orderRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final OutboxWriter writer;
    private final ObjectMapper json;

    @KafkaListener(topics = { "inventory-events",
            "payment-events" }, groupId = "order-service")
    @Transactional
    public void on(EventEnvelope<JsonNode> event) {
        if (processedEventRepository.existsById(event.eventId()))
            return;

        Order order = orderRepository.findById(Long.valueOf(event.aggregateId())).orElseThrow();
        OrderStatus before = order.getStatus();

        switch (event.eventType()) {
            case "InventoryReserved" -> order.awaitPayment();
            case "InventoryRejected" ->
                order.cancel(json.treeToValue(event.payload(), InventoryRejected.class).reason());
            case "PaymentCompleted" -> {
                if (order.getStatus() == OrderStatus.CANCELLED) {
                    var p = json.treeToValue(event.payload(), PaymentCompleted.class);
                    log.warn("Late payment {} for cancelled order {} {{}} - requesting refund", p.paymentReference(),
                            order.getId(), order.getCancelReason());
                    writer.write("RefundRequested", order.getId().toString(), new RefundRequested(order.getId(),
                            p.paymentReference(), p.amount(), p.currency(), p.customerName(), order.getCancelReason()));
                } else {
                    order.confirm();
                }
            }
            case "PaymentFailed" -> order.cancel(json.treeToValue(event.payload(), PaymentFailed.class).reason());
            default -> {
            }
        }

        if (order.getStatus() != before) {
            String orderId = order.getId().toString();
            switch (order.getStatus()) {
                case CONFIRMED -> writer.write("OrderConfirmed", orderId, new OrderConfirmed(order.getId(),
                        order.getOrderNumber(), order.getCustomerName(), order.getTotalAmount(), order.getCurrency()));
                case CANCELLED -> writer.write("OrderCancelled", orderId, OrderCancelled.from(order));
                default -> {
                }
            }
        }

        processedEventRepository.save(new ProcessedEvent(event.eventId(), Instant.now()));
    }
}
