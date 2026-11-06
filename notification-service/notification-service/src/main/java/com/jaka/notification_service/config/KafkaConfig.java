package com.jaka.notification_service.config;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.converter.JacksonJsonMessageConverter;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.util.backoff.ExponentialBackOff;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class KafkaConfig {
    @Bean
    RecordMessageConverter kafkaMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<Object, Object> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template);
        var backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxElapsedTime(10_000L);
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.setRetryListeners(new RetryListener() {
            @Override
            public void failedDelivery(ConsumerRecord<?, ?> record, @Nullable Exception ex, int deliveryAttempt) {
                log.warn("Attempt {} failed for {}-{}@{}: {}", deliveryAttempt,
                        record.topic(), record.partition(), record.offset(), ex == null ? null : ex.getMessage());
            }

            @Override
            public void recovered(ConsumerRecord<?, ?> record, Exception ex) {
                log.error("Gave up on {}-{}@{}, sent to DLT: {}",
                        record.topic(), record.partition(), record.offset(), ex.getMessage());
            }
        });
        return handler;
    }
}
