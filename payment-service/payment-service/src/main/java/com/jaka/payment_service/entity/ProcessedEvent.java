package com.jaka.payment_service.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity 
@Table(name = "processed_events")
@Getter 
@Setter 
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedEvent {
    
    @Id 
    @Column(length = 36)
    private String eventId;

    @Column(nullable = false)
    private Instant processedAt;
}
