package com.jaka.notification_service.dto;

import java.time.Instant;

// Copy of the shared event contract (TUAN-9 Chủ đề 1); no shared library between services
public record EventEnvelope<T>(
        String eventId,        // UUID — idempotency key
        String eventType,      // "OrderConfirmed"
        int version,           // 1
        String aggregateId,    // orderId — used as the Kafka key
        Instant occurredAt,
        T payload) {
}
