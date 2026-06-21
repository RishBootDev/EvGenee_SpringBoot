package com.voltx.evgenee.notification;

import com.voltx.evgenee.entity.Booking;
import com.voltx.evgenee.entity.RoadsideRequest;
import com.voltx.evgenee.entity.Vehicle;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
public class EmailNotificationPublisher {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final ApplicationEventPublisher events;

    public void passwordResetOtp(String email, String otp) {
        publish(email, "Access Code | EvGenee", "Verify Your Identity",
                "<p>Hello,</p>"
                        + "<p>Use the access code below to reset your EvGenee password.</p>"
                        + "<div class=\"otp-box\"><p>ONE-TIME ACCESS CODE</p>"
                        + "<h3 class=\"otp-code\">" + safe(otp) + "</h3></div>"
                        + "<p>This code expires in <span class=\"highlight\">10 minutes</span>. "
                        + "If you did not request it, you can ignore this email.</p>");
    }

    public void bookingConfirmed(Booking booking) {
        String email = booking.getUser().getAuthUser().getEmail();
        String name = booking.getUser().getFullName();
        String station = booking.getStation().getName();
        String vehicle = vehicleNumber(booking);
        String date = DATE.format(booking.getStartTime().atZone(IST));
        String time = TIME.format(booking.getStartTime().atZone(IST)) + " - "
                + TIME.format(booking.getEndTime().atZone(IST));

        publish(email, "Booking Confirmed | EvGenee", "Your Charger is Ready",
                greeting(name)
                        + "<p>Your charging slot at <span class=\"highlight\">" + safe(station)
                        + "</span> is confirmed.</p>"
                        + details("Date", date, "Time", time, "Vehicle", vehicle,
                        "Connector", booking.getConnectorType())
                        + "<p>Your secure check-in OTP is:</p>"
                        + "<div class=\"otp-box\"><h3 class=\"otp-code\">"
                        + safe(booking.getOtp()) + "</h3></div>");
    }

    public void bookingCancelled(Booking booking) {
        String email = booking.getUser().getAuthUser().getEmail();
        String time = TIME.format(booking.getStartTime().atZone(IST));
        publish(email, "Booking Cancelled | EvGenee", "Booking Update",
                greeting(booking.getUser().getFullName())
                        + "<p>Your booking at <span class=\"highlight\">"
                        + safe(booking.getStation().getName()) + "</span> for "
                        + safe(time) + " has been cancelled.</p>"
                        + "<p>Reason: " + safe(booking.getCancellationReason()) + "</p>"
                        + "<p>Any applicable refund will be returned through the original payment method.</p>");
    }

    public void bookingReminder(Booking booking) {
        String email = booking.getUser().getAuthUser().getEmail();
        String start = TIME.format(booking.getStartTime().atZone(IST));
        publish(email, "Session Reminder | EvGenee", "Almost Time to Charge",
                greeting(booking.getUser().getFullName())
                        + "<p>Your charging session starts in <span class=\"highlight\">15 minutes</span>.</p>"
                        + details("Station", booking.getStation().getName(), "Start Time", start,
                        "Vehicle", vehicleNumber(booking), "Connector", booking.getConnectorType())
                        + "<p>Please arrive a few minutes early for a smooth check-in.</p>");
    }

    public void roadsideDispatched(RoadsideRequest request, String userName) {
        boolean tow = Boolean.TRUE.equals(request.getTowRequested());
        String subject = tow ? "Tow Truck Dispatched | EvGenee SOS" : "Mechanic On The Way | EvGenee SOS";
        String title = tow ? "Tow Truck Has Been Dispatched" : "Help Is On The Way";
        String mapsUrl = "https://www.google.com/maps?q=" + request.getLatitude() + "," + request.getLongitude();

        publish(request.getUserEmail(), subject, title,
                greeting(userName)
                        + "<p>We received your SOS request and dispatched assistance to your location.</p>"
                        + details("Name", request.getMechanicName(), "Phone", request.getMechanicPhone(),
                        "Garage", request.getMechanicGarage(), "ETA", request.getMechanicEstimatedArrival())
                        + "<p>Issue: <span class=\"highlight\">" + safe(request.getIssueLabel()) + "</span></p>"
                        + "<p>Location: " + safe(request.getAddress()) + "</p>"
                        + "<p><a href=\"" + safe(mapsUrl) + "\">View shared location</a></p>"
                        + "<p>SOS ID: <span class=\"highlight\">" + request.getId() + "</span></p>");
    }

    private void publish(String to, String subject, String title, String content) {
        if (to == null || to.isBlank()) {
            return;
        }
        events.publishEvent(new EmailNotificationEvent(to, subject, title, content));
    }

    private String greeting(String name) {
        return "<p>Hello <span class=\"highlight\">" + safe(name) + "</span>,</p>";
    }

    private String details(String... values) {
        StringBuilder html = new StringBuilder("<div class=\"otp-box\" style=\"text-align:left\">");
        for (int index = 0; index + 1 < values.length; index += 2) {
            html.append("<p><span class=\"highlight\">")
                    .append(safe(values[index]))
                    .append(":</span> ")
                    .append(safe(values[index + 1]))
                    .append("</p>");
        }
        return html.append("</div>").toString();
    }

    private String vehicleNumber(Booking booking) {
        if (booking.getVehicleNumber() != null && !booking.getVehicleNumber().isBlank()) {
            return booking.getVehicleNumber();
        }
        Vehicle vehicle = booking.getVehicle();
        return vehicle != null ? vehicle.getLicensePlate() : "N/A";
    }

    private String safe(Object value) {
        return HtmlUtils.htmlEscape(value == null ? "N/A" : String.valueOf(value));
    }
}