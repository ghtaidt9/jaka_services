package com.jaka.notification_service.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jaka.notification_service.dto.EventEnvelope;
import com.jaka.notification_service.entity.Notification;
import com.jaka.notification_service.entity.NotificationChannel;
import com.jaka.notification_service.entity.NotificationStatus;
import com.jaka.notification_service.repository.NotificationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationTemplates templates;
    private final JavaMailSender mailSender;

    public List<Notification> getAllNotifications() {
        return notificationRepository.findAll();
    }

    @Transactional
    public void handle(EventEnvelope<JsonNode> event) {
        if (notificationRepository.existsByEventId(event.eventId())) {
            return; // đã xử lý rồi
        }
        Optional<NotificationTemplates.Message> msg = templates.render(event);
        if (msg.isEmpty()) {
            return; // event lạ → không báo
        }
        var m = msg.get();
        String recipient = m.customerName().toLowerCase().replaceAll("\\s+", ".") + "@example.com";

        notificationRepository.save(Notification.builder()
                .eventId(event.eventId())
                .eventType(event.eventType())
                .orderId(m.orderId())
                .channel(NotificationChannel.EMAIL)
                .recipient(recipient)
                .message(m.subject())
                .status(NotificationStatus.SENT)
                .sentAt(LocalDateTime.now())
                .build());
        notificationRepository.flush();

        var mail = new SimpleMailMessage();
        mail.setFrom("shop@jaka.local");
        mail.setTo(recipient);
        mail.setSubject(m.subject());
        mail.setText(m.body());
        mailSender.send(mail);
        log.info("Sent email to {}: {}", recipient, m.subject());
    }
}
