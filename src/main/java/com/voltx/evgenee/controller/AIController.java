package com.voltx.evgenee.controller;

import com.voltx.evgenee.dto.requests.AiChatRequest;
import com.voltx.evgenee.dto.responses.AiChatResponse;
import com.voltx.evgenee.service.AIService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AIController {

    private final AIService aiService;

    @PostMapping("/chat")
    public ResponseEntity<AiChatResponse> chat(
            @RequestBody AiChatRequest requestDto,
            Authentication authentication) {

        String email = authentication != null ? authentication.getName() : null;
        
        AiChatResponse response = aiService.processVoiceChat(
                requestDto.getMessage(),
                requestDto.getThreadId(),
                email,
                requestDto.getLocation() != null ? requestDto.getLocation().getLat() : null,
                requestDto.getLocation() != null ? requestDto.getLocation().getLng() : null
        );

        return ResponseEntity.ok(response);
    }
}
