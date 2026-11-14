package com.jaka.notification_service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.jaka.notification_service.dto.EventEnvelope;
import com.jaka.notification_service.dto.OrderCreated;
import com.jaka.notification_service.repository.NotificationRepository;
import com.jaka.notification_service.service.NotificationService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "management.health.mail.enabled=false")
@Import(TestcontainersConfiguration.class)
class NotificationHandleFlowTests {

    @MockitoBean
    JavaMailSender mailSender;

    @Autowired
    NotificationService notificationService;

    @Autowired
    NotificationRepository notificationRepository;

    @Autowired
    ObjectMapper json;

    @Test
    void handle_orderCreated_savesNotificationAndSendsMail() {
        var event = orderCreatedEvent(920001L);

        notificationService.handle(event);

        assertThat(notificationRepository.existsByEventId(event.eventId())).isTrue();
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void handle_sameEventTwice_sendsMailOnce() {
        var event = orderCreatedEvent(920002L);

        notificationService.handle(event);
        notificationService.handle(event);

        assertThat(notificationRepository.findAll())
                .filteredOn(n -> n.getEventId().equals(event.eventId()))
                .hasSize(1);
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void handle_unknownEventType_doesNothing() {
        var event = new EventEnvelope<JsonNode>(UUID.randomUUID().toString(), "Unknown", 1, "920003",
                Instant.now(), json.createObjectNode());

        notificationService.handle(event);

        assertThat(notificationRepository.existsByEventId(event.eventId())).isFalse();
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    private EventEnvelope<JsonNode> orderCreatedEvent(Long orderId) {
        var payload = new OrderCreated(orderId, "ORD-" + orderId, "alice", new BigDecimal("10.00"), "USD",
                "TEST-001", 1);
        return new EventEnvelope<>(UUID.randomUUID().toString(), "OrderCreated", 1, orderId.toString(),
                Instant.now(), json.valueToTree(payload));
    }
}
