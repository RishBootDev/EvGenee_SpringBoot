package com.voltx.evgenee.cron;

import com.voltx.evgenee.entity.Booking;
import com.voltx.evgenee.entity.EvUser;
import com.voltx.evgenee.repository.BookingRepository;
import com.voltx.evgenee.notification.EmailNotificationPublisher;
import com.voltx.evgenee.socket.RealtimeNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookingCronScheduler {

    private final BookingRepository bookingRepository;
    private final EmailNotificationPublisher emailNotifications;
    private final RealtimeNotificationService realtimeNotificationService;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private String lastReminderMinute = null;

    @Scheduled(cron = "0 */15 * * * *", zone = "Asia/Kolkata")
    @Transactional
    public void markNoShows() {
        try {
            Instant now = Instant.now();
            int count = bookingRepository.markNoShows(now);
            if (count > 0) {
                log.info("[CRON] Marked {} bookings as NO_SHOW", count);
            }
        } catch (Exception e) {
            log.error("[CRON] Error marking no-shows: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 */10 * * * *", zone = "Asia/Kolkata")
    @Transactional
    public void autoCompleteBookings() {
        try {
            Instant now = Instant.now();
            int count = bookingRepository.autoCompleteBookings(now);
            if (count > 0) {
                log.info("[CRON] Auto-completed {} bookings", count);
                realtimeNotificationService.notifyAutoCompleted(count);
            }
        } catch (Exception e) {
            log.error("[CRON] Error auto-completing bookings: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 * * * * *")
    @Transactional
    public void expirePendingBookings() {
        try {
            Instant tenMinutesAgo = Instant.now().minus(10, ChronoUnit.MINUTES);
            int count = bookingRepository.expirePendingBookings(tenMinutesAgo);
            if (count > 0) {
                log.info("[CRON] Expired {} pending bookings", count);
                realtimeNotificationService.notifyCapacityChanged(null, null, "expiration");
            }
        } catch (Exception e) {
            log.error("[CRON] Error expiring pending bookings: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Kolkata")
    @Transactional
    public void sendBookingReminders() {
        try {
            ZonedDateTime nowIST = ZonedDateTime.now(IST);
            String currentMinute = nowIST.format(TIME_FMT);

            if (currentMinute.equals(lastReminderMinute)) {
                return;
            }
            lastReminderMinute = currentMinute;

            Instant windowStart = Instant.now().plus(15, ChronoUnit.MINUTES);
            Instant windowEnd = windowStart.plus(1, ChronoUnit.MINUTES);
            List<Booking> upcomingBookings = bookingRepository.findBookingsForReminder(windowStart, windowEnd);

            for (Booking booking : upcomingBookings) {
                try {
                    EvUser evUser = booking.getUser();
                    if (evUser == null || evUser.getAuthUser() == null) continue;

                    String userEmail = evUser.getAuthUser().getEmail();
                    String startTimeStr = ZonedDateTime.ofInstant(booking.getStartTime(), IST).format(TIME_FMT);

                    booking.setReminderSent(true);
                    bookingRepository.save(booking);
                    emailNotifications.bookingReminder(booking);

                    realtimeNotificationService.notifyBookingReminder(userEmail, booking.getId().toString(), startTimeStr);

                    log.info("[CRON] Reminder queued for {} for session at {}", userEmail, startTimeStr);

                } catch (Exception e) {
                    log.error("[CRON] Failed to send reminder for booking {}: {}", booking.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[CRON] Error in reminder cron: {}", e.getMessage());
        }
    }
}
