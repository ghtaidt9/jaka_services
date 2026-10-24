package com.jaka.payment_service.config;

import io.micrometer.cloudwatch2.CloudWatchConfig;
import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;

import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "cloudwatch.metrics.enabled", havingValue = "true")
public class CloudWatchMetricsConfig {
    @Bean(destroyMethod = "close")
    public CloudWatchAsyncClient cloudWatchAsyncClient() {
        return CloudWatchAsyncClient.create();
    }

    @Bean(destroyMethod = "close")
    public CloudWatchMeterRegistry cloudWatchMeterRegistry(CloudWatchAsyncClient cloudWatchAsyncClient,
                                                           @Value("${cloudwatch.metrics.namespace:Microservices/App}") String namespace) {
        CloudWatchConfig config = new CloudWatchConfig() {
            @Override
            public String get(String key) {
                return null; // Use default values for all other properties
            }

            @Override
            public String namespace() {
                return namespace;
            }

            @Override
            public Duration step() {
                return Duration.ofMinutes(1); // Set the step duration to 1 minute
            }
        };
        CloudWatchMeterRegistry registry = new CloudWatchMeterRegistry(config, Clock.SYSTEM, cloudWatchAsyncClient);
        registry.config().meterFilter(MeterFilter.denyUnless(
            id -> id.getName().startsWith("http.server.requests") 
            || id.getName().startsWith("hikaricp.connections") 
            || id.getName().startsWith("cache.gets")));
        return registry;
    }
}
