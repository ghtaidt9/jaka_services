package com.jaka.notification_service.common;

public final class Correlation {
    public static final String HTTP_HEADER = "X-Correlation-Id";
    public static final String KAFKA_HEADER = "correlationId";
    public static final String MDC_KEY = "correlationId";
    private Correlation() {}
}
