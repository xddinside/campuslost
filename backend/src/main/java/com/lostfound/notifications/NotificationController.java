package com.lostfound.notifications;

import com.lostfound.model.User;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public NotificationPageResponse list(@AuthenticationPrincipal User user,
                                         @RequestParam(defaultValue = "20") int limit,
                                         @RequestParam(required = false) Long beforeId) {
        return notifications.page(user, limit, beforeId);
    }

    @PatchMapping("/{id}/read")
    public NotificationResponse markRead(@AuthenticationPrincipal User user,
                                         @PathVariable Long id) {
        return notifications.markRead(user, id);
    }

    @PostMapping("/read-all")
    public UnreadCountResponse markAllRead(@AuthenticationPrincipal User user) {
        return notifications.markAllRead(user);
    }
}
