package com.voltx.evgenee.socket;

import com.voltx.evgenee.dto.responses.AiChatResponse;
import com.voltx.evgenee.service.AIService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Controller
@RequiredArgsConstructor
@Slf4j
public class WebSocketEventController {

    private final SimpMessagingTemplate messagingTemplate;
    private final AIService aiService;

    @MessageMapping("/ping")
    public void handlePing(SimpMessageHeaderAccessor accessor) {
        String sessionId = accessor.getSessionId();
        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", Instant.now().toString());
        messagingTemplate.convertAndSendToUser(sessionId, "/queue/pong", response);
        log.info("[WS] Pong sent to session: {}", sessionId);
    }

    @MessageMapping("/ai/voice_chat")
    public void handleVoiceChat(@Payload VoiceChatRequest request,
                                Principal principal,
                                SimpMessageHeaderAccessor accessor) {
        String sessionId = accessor.getSessionId();

        if (principal == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "Authentication required");
            messagingTemplate.convertAndSendToUser(sessionId, "/queue/ai/voice_response", error);
            return;
        }

        String userEmail = principal.getName();
        log.info("[WS] AI voice_chat from user: {}", userEmail);

        try {
            String threadId = (request.getThreadId() != null && !request.getThreadId().isBlank())
                    ? request.getThreadId()
                    : sessionId;

            Double lat = (request.getLocation() != null) ? request.getLocation().getLat() : null;
            Double lng = (request.getLocation() != null) ? request.getLocation().getLng() : null;

            AiChatResponse aiResult = aiService.processVoiceChat(request.getMessage(), threadId, userEmail, lat, lng);

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

            messagingTemplate.convertAndSendToUser(userEmail, "/queue/ai/voice_response", response);

        } catch (Exception e) {
            log.error("[WS] AI voice_chat error: ", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "Failed to process chat: " + e.getMessage());
            assert sessionId != null;
            messagingTemplate.convertAndSendToUser(sessionId, "/queue/ai/voice_response", error);
        }
    }
}
