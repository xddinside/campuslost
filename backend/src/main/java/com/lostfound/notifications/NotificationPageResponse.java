package com.lostfound.notifications;

import java.util.List;

public record NotificationPageResponse(
        List<NotificationResponse> notifications,
        long unreadCount,
        Long nextBeforeId
) {
}
