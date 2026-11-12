package com.pause.backend;

import java.time.LocalDateTime;

public record NotificationResponse(
        Long id,
        Long paymentRecordId,
        String type,
        String title,
        String message,
        LocalDateTime createdAt,
        boolean read
) {
    public static NotificationResponse from(UserNotification notification) {
        return new NotificationResponse(notification.getId(), notification.getPaymentRecordId(),
                notification.getType(), notification.getTitle(), notification.getMessage(),
                notification.getCreatedAt(), notification.isRead());
    }
}
