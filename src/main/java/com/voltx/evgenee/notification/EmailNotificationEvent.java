package com.voltx.evgenee.notification;

public record EmailNotificationEvent(
        String to,
        String subject,
        String title,
        String htmlContent
) {
}