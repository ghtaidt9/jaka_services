package com.jaka.notification_service.config;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.stereotype.Component;

import com.jaka.notification_service.common.Correlation;

@Component
public class CorrelationIdInterceptor implements RecordInterceptor<Object, Object> {

    private static final ThreadLocal<String> PREVIOUS = new ThreadLocal<>();

    @Override
    public @Nullable ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
            Consumer<Object, Object> consumer) {
        PREVIOUS.set(MDC.get(Correlation.MDC_KEY));
        Header h = record.headers().lastHeader(Correlation.KAFKA_HEADER);
        if (h != null) {
            MDC.put(Correlation.MDC_KEY, new String(h.value(), StandardCharsets.UTF_8));
        }
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        String previous = PREVIOUS.get();
        PREVIOUS.remove();
        if (previous != null) {
            MDC.put(Correlation.MDC_KEY, previous);
        } else {
            MDC.remove(Correlation.MDC_KEY);
        }
    }

}
