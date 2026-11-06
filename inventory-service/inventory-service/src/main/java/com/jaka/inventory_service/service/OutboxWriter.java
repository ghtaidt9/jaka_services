package com.jaka.inventory_service.service;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.jaka.inventory_service.common.Correlation;
import com.jaka.inventory_service.dto.EventEnvelope;
import com.jaka.inventory_service.entity.OutboxEvent;
import com.jaka.inventory_service.repository.OutboxRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component 
@RequiredArgsConstructor
public class OutboxWriter {
    private final OutboxRepository outbox;
    private final ObjectMapper json;

    public void write(String eventType, String aggregateId, Object payload) {
        var event = new EventEnvelope<>(UUID.randomUUID().toString(), eventType, 1, aggregateId, Instant.now(), payload);
        String cid = MDC.get(Correlation.MDC_KEY);
        if (cid == null) {
            cid = UUID.randomUUID().toString();
        }
        outbox.save(OutboxEvent.of(event.eventId(), eventType, aggregateId, json.writeValueAsString(event), cid));
    }
}
