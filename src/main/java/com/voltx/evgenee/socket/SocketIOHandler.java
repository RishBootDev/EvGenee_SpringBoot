package com.voltx.evgenee.socket;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnConnect;
import com.corundumstudio.socketio.annotation.OnDisconnect;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.voltx.evgenee.dto.responses.AiChatResponse;
import com.voltx.evgenee.enums.Role;
import com.voltx.evgenee.exceptions.UnauthorizedException;
import com.voltx.evgenee.repository.UserRepository;
import com.voltx.evgenee.service.AIService;
import com.voltx.evgenee.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class SocketIOHandler {

    private final SocketIOServer server;
    private final JwtUtil jwtUtil;
    private final AIService aiService;
    private final UserRepository userRepository;

    @OnConnect
    public void onConnect(SocketIOClient client) {
        String token = client.getHandshakeData().getSingleUrlParam("token");
        if (token == null) {
            String authParam = client.getHandshakeData().getSingleUrlParam("auth");
            if (authParam != null && authParam.startsWith("{") && authParam.contains("token")) {
                try {
                    int index = authParam.indexOf("\"token\"");
                    if (index != -1) {
                        int start = authParam.indexOf(":", index);
                        if (start != -1) {
                            int firstQuote = authParam.indexOf("\"", start);
                            int secondQuote = authParam.indexOf("\"", firstQuote + 1);
                            if (firstQuote != -1 && secondQuote != -1) {
                                token = authParam.substring(firstQuote + 1, secondQuote);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        if (token == null) {
           throw new UnauthorizedException("jwt token not found");
        }
        try {
            String email = jwtUtil.extractEmail(token);
            if (email != null && jwtUtil.validateToken(token, email)) {
                client.set("email", email);
            }
        } catch (Exception ignored) {
        }
    }

    @OnDisconnect
    public void onDisconnect(SocketIOClient client) {
    }

    @OnEvent("station:subscribe")
    public void onStationSubscribe(SocketIOClient client, String stationId) {
        client.joinRoom("station_" + stationId);
        Map<String, String> response = new HashMap<>();
        response.put("stationId", stationId);
        response.put("message", "Now receiving real-time updates for station " + stationId);
        client.sendEvent("station:subscribed", response);
    }

    @OnEvent("station:unsubscribe")
    public void onStationUnsubscribe(SocketIOClient client, String stationId) {
        client.leaveRoom("station_" + stationId);
    }

    @OnEvent("user:subscribe")
    public void onUserSubscribe(SocketIOClient client, String userId) {
        client.joinRoom("user_" + userId);
    }

    @OnEvent("admin:subscribe")
    public void onAdminSubscribe(SocketIOClient client) {
        String email = client.get("email");
        boolean admin = email != null && userRepository.findByEmail(email)
                .map(user -> Role.ADMIN.equals(user.getRole()))
                .orElse(false);
        if (!admin) {
            throw new UnauthorizedException("admin access required");
        }
        client.joinRoom("admin");
        Map<String, String> response = new HashMap<>();
        response.put("message", "Now receiving real-time admin updates");
        client.sendEvent("admin:subscribed", response);
    }

    @OnEvent("ping")
    public void onPing(SocketIOClient client) {
        Map<String, String> response = new HashMap<>();
        response.put("timestamp", Instant.now().toString());
        client.sendEvent("pong", response);
    }

    @OnEvent("ai:voice_chat")
    public void onVoiceChat(SocketIOClient client, VoiceChatRequest request) {
        String threadId = (request.getThreadId() != null && !request.getThreadId().isBlank())
                ? request.getThreadId()
                : client.getSessionId().toString();

        String email = client.get("email");
        Double lat = (request.getLocation() != null) ? request.getLocation().getLat() : null;
        Double lng = (request.getLocation() != null) ? request.getLocation().getLng() : null;

        try {
            AiChatResponse aiResult = aiService.processVoiceChat(request.getMessage(), threadId, email, lat, lng);

            Map<String, Object> response = new HashMap<>();
            response.put("success", aiResult.isSuccess());
            response.put("threadId", aiResult.getData().getThreadId());
            response.put("response", aiResult.getData().getResponse());
            if (aiResult.getData().getBookingId() != null) {
                response.put("bookingId", aiResult.getData().getBookingId());
                response.put("redirect", aiResult.getData().getRedirect());
            }
            if (aiResult.getData().getStations() != null) {
                response.put("stations", aiResult.getData().getStations());
            }
            if (aiResult.getData().getCheckout() != null) {
                response.put("checkout", aiResult.getData().getCheckout());
            }
            client.sendEvent("ai:voice_response", response);
        } catch (Exception e) {
            log.error("Error processing Socket.IO voice chat", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "Failed to process chat: " + e.getMessage());
            client.sendEvent("ai:voice_response", error);
        }
    }
}
