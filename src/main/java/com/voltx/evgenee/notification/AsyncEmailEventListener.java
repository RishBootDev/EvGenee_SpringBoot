package com.voltx.evgenee.notification;

import com.voltx.evgenee.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class AsyncEmailEventListener {

    private final EmailService emailService;

    @Async("mailTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void send(EmailNotificationEvent event) {
        try {
            emailService.sendEmail(event.to(), event.subject(), event.title(), event.htmlContent());
        } catch (Exception exception) {
            log.error("Asynchronous email delivery failed for {}: {}", event.to(), exception.getMessage());
        }
    }
}