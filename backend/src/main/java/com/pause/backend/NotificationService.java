package com.pause.backend;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class NotificationService {

    private final UserNotificationRepository notificationRepository;

    public NotificationService(UserNotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Transactional
    public void createOnce(Long userId, Long paymentId, String event, String title, String message) {
        String eventKey = "payment:" + paymentId + ":" + event;
        if (notificationRepository.existsByEventKey(eventKey)) return;

        UserNotification notification = new UserNotification();
        notification.setUserId(userId);
        notification.setPaymentRecordId(paymentId);
        notification.setEventKey(eventKey);
        notification.setType(event);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setCreatedAt(LocalDateTime.now());
        notificationRepository.save(notification);
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> listForUser(Long userId) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(NotificationResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return notificationRepository.countByUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public Optional<NotificationResponse> markRead(Long notificationId, Long userId) {
        return notificationRepository.findByIdAndUserId(notificationId, userId).map(notification -> {
            if (notification.getReadAt() == null) {
                notification.setReadAt(LocalDateTime.now());
                notificationRepository.save(notification);
            }
            return NotificationResponse.from(notification);
        });
    }
}
