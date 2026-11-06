package com.jaka.payment_service.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "outbox")
public class OutboxEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @Column(nullable = false, unique = true)
    String eventId;
    @Column(nullable = false)
    String aggregateId;
    @Column(nullable = false)
    String eventType;
    @Column(nullable = false, columnDefinition = "text")
    String payload;
    @Column(nullable = false)
    Instant createdAt;
    @Column()
    Instant publishedAt;
    @Column(nullable = true)
    String correlationId;

    public static OutboxEvent of(String eventId, String eventType, String aggregateId, String payload, String cid) {
        return OutboxEvent.builder()
                .eventId(eventId)
                .eventType(eventType)
                .aggregateId(aggregateId)
                .payload(payload)
                .createdAt(Instant.now())
                .correlationId(cid)
                .build();
    }
}
