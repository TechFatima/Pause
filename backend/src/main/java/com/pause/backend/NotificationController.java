package com.pause.backend;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final UserIdentityService userIdentityService;
    private final NotificationService notificationService;

    public NotificationController(
            UserIdentityService userIdentityService,
            NotificationService notificationService
    ) {
        this.userIdentityService = userIdentityService;
        this.notificationService = notificationService;
    }

    @GetMapping
    public List<NotificationResponse> list(Authentication authentication) {
        Long userId = userIdentityService.requireUser(authentication).getId();
        return notificationService.listForUser(userId);
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<NotificationResponse> markRead(
            @PathVariable Long id,
            Authentication authentication
    ) {
        Long userId = userIdentityService.requireUser(authentication).getId();
        return notificationService.markRead(id, userId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
