package com.jaka.payment_service.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectivityStartupLogger implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            log.info("Postgres connectivity check: OK (SELECT 1 = {})", result);
        } catch (Exception e) {
            log.error("Postgres connectivity check: FAILED", e);
        }

        try {
            String pong = redisTemplate.getConnectionFactory().getConnection().ping();
            log.info("Redis connectivity check: OK (PING = {})", pong);
        } catch (Exception e) {
            log.error("Redis connectivity check: FAILED", e);
        }
    }
}
