package com.jaka.payment_service.relay;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.jaka.payment_service.common.Correlation;
import com.jaka.payment_service.entity.OutboxEvent;
import com.jaka.payment_service.repository.OutboxRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {
    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void relay() {
        for (OutboxEvent event : outbox.lockUnpublishedEvents(100)) {
            var record = new ProducerRecord<>("payment-events", event.getAggregateId(), event.getPayload());
            if (event.getCorrelationId() != null) {
                record.headers().add(Correlation.KAFKA_HEADER,
                        event.getCorrelationId().getBytes(StandardCharsets.UTF_8));
            }
            record.headers().add("eventType", event.getEventType().getBytes(StandardCharsets.UTF_8));
            String previousCId = MDC.get(Correlation.MDC_KEY);
            try {
                if (event.getCorrelationId() != null) {
                    MDC.put(Correlation.MDC_KEY, event.getCorrelationId());
                }
                kafka.send(record).join();
                log.info("Published {} {} for aggregate {}", event.getEventType(), event.getEventId(), event.getAggregateId());
            } finally {
                if (previousCId != null) {
                    MDC.put(Correlation.MDC_KEY, previousCId);
                } else {
                    MDC.remove(Correlation.MDC_KEY);
                }
            }
            event.setPublishedAt(Instant.now());
        }
    }
}
