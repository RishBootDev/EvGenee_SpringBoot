package com.voltx.evgenee.socket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import com.corundumstudio.socketio.SocketIOServer;
import com.voltx.evgenee.repository.UserRepository;
import com.voltx.evgenee.entity.User;
import java.util.Optional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class RealtimeNotificationService {

    private final SimpMessagingTemplate messagingTemplate;
    private final SocketIOServer socketIOServer;
    private final UserRepository userRepository;

    public void emitToStation(String stationId, String event, Object payload) {
        messagingTemplate.convertAndSend("/topic/station/" + stationId + "/" + event, payload);
        socketIOServer.getRoomOperations("station_" + stationId).sendEvent(event, payload);
        log.info("[WS] Emitted '{}' to station room: station_{}", event, stationId);
    }

    public void emitToUser(String userEmail, String event, Object payload) {
        messagingTemplate.convertAndSendToUser(userEmail, "/queue/" + event, payload);
        Optional<User> userOpt = userRepository.findByEmail(userEmail);
        if (userOpt.isPresent()) {
            String userId = String.valueOf(userOpt.get().getId());
            socketIOServer.getRoomOperations("user_" + userId).sendEvent(event, payload);
        }
        log.info("[WS] Emitted '{}' to user: {}", event, userEmail);
    }

    public void emitToAll(String event, Object payload) {
        messagingTemplate.convertAndSend("/topic/" + event, payload);
        socketIOServer.getBroadcastOperations().sendEvent(event, payload);
        log.info("[WS] Broadcast '{}' to all clients", event);
    }

    public void notifyBookingCreated(String stationId, String userEmail, String bookingId,
                                     String connectorType, String startTime, String endTime, String date) {
        SocketPayload stationPayload = SocketPayload.builder()
                .stationId(stationId)
                .bookingId(bookingId)
                .connectorType(connectorType)
                .startTime(startTime)
                .endTime(endTime)
                .date(date)
                .timestamp(Instant.now().toString())
                .build();
        emitToStation(stationId, "booking:created", stationPayload);

        SocketPayload userPayload = SocketPayload.builder()
                .bookingId(bookingId)
                .stationId(stationId)
                .status("confirmed")
                .build();
        emitToUser(userEmail, "booking:created", userPayload);
    }

    public void notifyAvailabilityUpdated(String stationId, String date, Long activeBookings, Integer totalPorts) {
        SocketPayload payload = SocketPayload.builder()
                .stationId(stationId)
                .date(date)
                .data(java.util.Map.of(
                        "activeBookings", activeBookings,
                        "totalPorts", totalPorts
                ))
                .timestamp(Instant.now().toString())
                .build();
        emitToStation(stationId, "availability:updated", payload);
    }

    public void notifyCapacityChanged(String stationId, String connectorType, String status) {
        SocketPayload payload = SocketPayload.builder()
                .stationId(stationId)
                .connectorType(connectorType)
                .status(status)
                .timestamp(Instant.now().toString())
                .build();
        emitToAll("station:capacity_changed", payload);
    }

    public void notifyBookingCancelled(String stationId, String userEmail, String bookingId,
                                       String startTime, String endTime, String date) {
        SocketPayload stationPayload = SocketPayload.builder()
                .bookingId(bookingId)
                .stationId(stationId)
                .date(date)
                .startTime(startTime)
                .endTime(endTime)
                .build();
        emitToStation(stationId, "booking:cancelled", stationPayload);

        SocketPayload userPayload = SocketPayload.builder()
                .bookingId(bookingId)
                .status("cancelled")
                .build();
        emitToUser(userEmail, "booking:cancelled", userPayload);
    }

    public void notifyCheckedIn(String stationId, String userEmail, String bookingId, Instant checkedInAt) {
        SocketPayload stationPayload = SocketPayload.builder()
                .bookingId(bookingId)
                .stationId(stationId)
                .data(checkedInAt)
                .build();
        emitToStation(stationId, "booking:checkedIn", stationPayload);

        SocketPayload userPayload = SocketPayload.builder()
                .bookingId(bookingId)
                .status("in-progress")
                .build();
        emitToUser(userEmail, "booking:checkedIn", userPayload);
    }

    public void notifyCompleted(String stationId, String userEmail, String bookingId, Instant completedAt) {
        SocketPayload stationPayload = SocketPayload.builder()
                .bookingId(bookingId)
                .stationId(stationId)
                .data(completedAt)
                .build();
        emitToStation(stationId, "booking:completed", stationPayload);

        SocketPayload userPayload = SocketPayload.builder()
                .bookingId(bookingId)
                .status("completed")
                .build();
        emitToUser(userEmail, "booking:completed", userPayload);
    }

    public void notifyStationUpdated(String stationId, Object updates) {
        SocketPayload payload = SocketPayload.builder()
                .stationId(stationId)
                .data(updates)
                .timestamp(Instant.now().toString())
                .build();
        emitToStation(stationId, "station:updated", payload);
    }

    public void notifyStationStatusChanged(String stationId, String stationName, Boolean isOpen) {
        SocketPayload payload = SocketPayload.builder()
                .stationId(stationId)
                .name(stationName)
                .isOpen(isOpen)
                .build();
        emitToAll("station:statusChanged", payload);
    }

    public void notifyBookingReminder(String userEmail, String bookingId, String startTime) {
        SocketPayload payload = SocketPayload.builder()
                .bookingId(bookingId)
                .message("Your charging session starts at " + startTime + ". Be ready!")
                .build();
        emitToUser(userEmail, "booking:reminder", payload);
    }

    public void notifyAutoCompleted(long count) {
        SocketPayload payload = SocketPayload.builder()
                .count(count)
                .timestamp(Instant.now().toString())
                .build();
        emitToAll("bookings:autoCompleted", payload);
    }
}
