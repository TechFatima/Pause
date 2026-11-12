package com.pause.backend;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserNotificationRepository extends JpaRepository<UserNotification, Long> {
    boolean existsByEventKey(String eventKey);
    List<UserNotification> findByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<UserNotification> findByIdAndUserId(Long id, Long userId);
    long countByUserIdAndReadAtIsNull(Long userId);
}
