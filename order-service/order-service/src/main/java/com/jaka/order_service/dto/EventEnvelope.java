package com.jaka.order_service.dto;

import java.time.Instant;

public record EventEnvelope<T>(String eventId, String eventType, int version, String aggregateId, Instant occurredAt, T payload ) {

}