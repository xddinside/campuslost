package com.lostfound.notifications;

import java.time.Instant;

/**
 * One alert as shown in the inbox. Navigation is derived from these IDs and
 * fixed application routes; callers never supply a destination URL.
 */
public record NotificationResponse(
        Long id,
        NotificationType type,
        String title,
        String body,
        Long itemId,
        Long claimId,
        Long lostReportId,
        Instant createdAt,
        Instant readAt
) {
}
