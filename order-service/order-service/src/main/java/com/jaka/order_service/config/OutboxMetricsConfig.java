package com.jaka.order_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.jaka.order_service.repository.OutboxRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration
public class OutboxMetricsConfig {

    @Bean
    Gauge outboxPendingGauge(MeterRegistry registry, OutboxRepository repository) {
        return Gauge.builder("outbox.pending", repository, r -> r.countByPublishedAtIsNull())
                .description("Number of outbox events not yet published")
                .register(registry);
    }
}
