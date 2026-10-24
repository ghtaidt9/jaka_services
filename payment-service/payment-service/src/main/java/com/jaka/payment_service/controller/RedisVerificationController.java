package com.jaka.payment_service.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class RedisVerificationController {

    private final StringRedisTemplate redisTemplate;

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    @GetMapping("/redis-check")
    public Map<String, Object> redisCheck() {
        String key = "healthcheck:payment-service:" + System.currentTimeMillis();
        String value = "ok-" + System.currentTimeMillis();

        redisTemplate.opsForValue().set(key, value);
        String readBack = redisTemplate.opsForValue().get(key);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", value.equals(readBack) ? "UP" : "DOWN");
        result.put("host", redisHost);
        result.put("port", redisPort);
        result.put("key", key);
        result.put("value", readBack);
        return result;
    }
}
