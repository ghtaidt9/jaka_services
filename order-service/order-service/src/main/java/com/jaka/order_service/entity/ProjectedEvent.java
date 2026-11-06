package com.jaka.order_service.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "projected_events")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ProjectedEvent {

    @Id
    @Column(length = 36)
    private String eventId;
    
    @Column(nullable = false)
    private Instant projectedAt;

}
