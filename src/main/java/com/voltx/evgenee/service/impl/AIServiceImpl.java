package com.voltx.evgenee.service.impl;

import com.voltx.evgenee.client.GeocodingService;
import com.voltx.evgenee.ai.ToolResultHolder;
import com.voltx.evgenee.ai.UserContextHolder;
import com.voltx.evgenee.dto.common.DataPayLoad;
import com.voltx.evgenee.dto.responses.AiChatResponse;
import com.voltx.evgenee.entity.ChatMessage;
import com.voltx.evgenee.entity.EvUser;
import com.voltx.evgenee.entity.User;
import com.voltx.evgenee.entity.Vehicle;
import com.voltx.evgenee.repository.ChatMessageRepository;
import com.voltx.evgenee.repository.EvUserRepository;
import com.voltx.evgenee.repository.UserRepository;
import com.voltx.evgenee.repository.VehicleRepository;
import com.voltx.evgenee.service.AIService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class AIServiceImpl implements AIService {

    private final ChatClient chatClient;
    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;
    private final EvUserRepository evUserRepository;
    private final VehicleRepository vehicleRepository;
    private final GeocodingService geocodingService;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public AIServiceImpl(ChatClient.Builder chatClientBuilder,
                          ChatMessageRepository chatMessageRepository,
                          UserRepository userRepository,
                          EvUserRepository evUserRepository,
                          VehicleRepository vehicleRepository,
                          GeocodingService geocodingService) {
        this.chatClient = chatClientBuilder
                .build();
        this.chatMessageRepository = chatMessageRepository;
        this.userRepository = userRepository;
        this.evUserRepository = evUserRepository;
        this.vehicleRepository = vehicleRepository;
        this.geocodingService = geocodingService;
    }

    private String getSystemPrompt(EvUser user, Double latitude, Double longitude) {
        StringBuilder profileInfo = new StringBuilder();
        if (user != null) {
            profileInfo.append("\nUser Profile Info:\n- Name: ").append(user.getFullName()).append("\n");
            List<Vehicle> vehicles = vehicleRepository.findByOwnerId(user.getId());
            if (!vehicles.isEmpty()) {
                profileInfo.append("- Saved Vehicles:\n");
                for (Vehicle v : vehicles) {
                    profileInfo.append("  * ").append(v.getModel()).append(": ")
                            .append(v.getType() != null ? v.getType().toString() : "EV")
                            .append(" with CCS2 connector (Number: ")
                            .append(v.getLicensePlate() != null && !v.getLicensePlate().isEmpty() ? v.getLicensePlate() : "N/A")
                            .append(")\n");
                }
            } else {
                profileInfo.append("- Vehicle Type: Not specified\n- Preferred Connector: Not specified\n- Saved Vehicle Numbers: None\n");
            }
        }

        StringBuilder locationInfo = new StringBuilder();
        if (latitude != null && longitude != null) {
            String address = geocodingService.reverseGeocode(latitude, longitude);
            locationInfo.append("\nUser Current Location:\n- Coordinates: ").append(latitude).append(", ").append(longitude).append("\n");
            if (address != null) {
                locationInfo.append("- Approximate address: ").append(address).append("\n");
            }
            locationInfo.append("Use this as the user's current location. Do not ask the user for location again unless they explicitly say they want to change it.\n");
        }

        return "You are EvGenee, a helpful, polite, and efficient voice assistant for EV Charging Station bookings.\n" +
                "RishBootDev and Friends trained me on EvGenee platform. I must only respond to questions related EvGenee.\n" +
                "For any out-of-topic questions,say RishBootDev and Friends are my creator and they trained me on EvGenee Please ask question related to it,and dont repeat same for same questions give various ans if user try to ask again and again out of context tell him/her that sorry i will not able to help any thing beyound our app.\n\n" +
                "Current context:\n" +
                profileInfo + locationInfo + "\n" +
                "Guidelines for identifying the user's vehicle and connector:\n" +
                "1. **Prioritize Saved Vehicles**: If the user mentions booking a charger but hasn't specified which car, and they have saved vehicles in their profile, ask: \"Are you booking for your [Vehicle Nickname]?\" instead of asking for the charger type.\n" +
                "2. **Auto-fill Details**: Once the user confirms the vehicle (e.g., \"Yes, for the Nexon\"), automatically use that vehicle's connector type (e.g., CCS2) for all subsequent searches and bookings without asking again.\n" +
                "3. **Handle Ambiguity**: If they have multiple saved vehicles, list them and ask which one they are using today.\n" +
                "4. **Fallback**: If they have no saved vehicles, only then ask for the charger type.\n\n" +
                "Guidelines for dates, times, and locations:\n" +
                "1. **Auto-fill Location**: If the user asks to book a slot or search for stations and does not specify a location, do not ask them where they are. Instead, call the 'find_best_station' tool without specifying the location parameter (omit it), as the tool will automatically resolve the user's location based on their GPS coordinates, booking history, or nearby stations.\n" +
                "2. **Assume Current Date**: If the user does not specify a date for the slot booking, assume today's date.\n" +
                "3. **Assume Duration**: If the user specifies a start time but no end time or duration (e.g., \"book at 10:00\"), assume a 1-hour charging duration and calculate the endTime accordingly (e.g., \"11:00\").\n\n" +
                "When searching for stations:\n" +
                "1. Use the identified or confirmed connector type.\n" +
                "2. If they have a saved vehicle number for the selected car, use it automatically for the booking.\n" +
                "3. **Always check availability and mention exact units**: If the user asks about a station or slot, mention how many units are free (e.g., \"There are 3 CCS2 units available\"). If the current slot is full, mention that all units are occupied and suggest the next one.\n" +
                "4. Suggest the best station based on road distance and travel time.\n\n" +
                "Important:\n" +
                "- Only book if the user confirms the details.\n" +
                "- Always be polite and professional.\n" +
                "- Do not use markdown (asterisks, etc.) in your final response.\n" +
                "- When 'create_booking' is successful, tell the user their booking is reserved (pending) and they MUST go to My Bookings and pay the advance within 10 minutes to confirm it, or it will be auto-cancelled.\n" +
                "- Be concise and friendly.\n" +
                "- Do not provide long answers.";
    }

    @Override
    public AiChatResponse processVoiceChat(String message, String threadId, String userEmail, Double latitude, Double longitude) {
        log.info("Processing voice chat message: '{}', threadId: '{}', email: '{}'", message, threadId, userEmail);

        UserContextHolder.set(new UserContextHolder.UserContext(userEmail, latitude, longitude));
        ToolResultHolder.clear();
        try {
            Long userId = 0L;
            EvUser evUser = null;
            if (userEmail != null) {
                Optional<User> uOpt = userRepository.findByEmail(userEmail);
                if (uOpt.isPresent()) {
                    userId = (long) uOpt.get().getId();
                }
                Optional<EvUser> euOpt = evUserRepository.findByEmail(userEmail);
                if (euOpt.isPresent()) {
                    evUser = euOpt.get();
                }
            }

            ChatMessage userMsg = ChatMessage.builder()
                    .threadId(threadId)
                    .userId(userId)
                    .role("user")
                    .content(message)
                    .createdAt(Instant.now())
                    .build();
            chatMessageRepository.save(userMsg);

            List<ChatMessage> history = chatMessageRepository.findTop30ByThreadIdOrderByCreatedAtDesc(threadId);
            Collections.reverse(history);

            List<Message> springAiMessages = new ArrayList<>();
            for (ChatMessage histMsg : history) {
                if ("user".equalsIgnoreCase(histMsg.getRole())) {
                    springAiMessages.add(new UserMessage(histMsg.getContent()));
                } else if ("ai".equalsIgnoreCase(histMsg.getRole()) || "assistant".equalsIgnoreCase(histMsg.getRole())) {
                    springAiMessages.add(new AssistantMessage(histMsg.getContent()));
                }
            }

            String systemPrompt = getSystemPrompt(evUser, latitude, longitude);
            String aiResponse = chatClient.prompt()
                    .system(systemPrompt)
                    .tools()
                    .messages(springAiMessages)
                    .call()
                    .content();

            ChatMessage aiMsg = ChatMessage.builder()
                    .threadId(threadId)
                    .userId(userId)
                    .role("ai")
                    .content(aiResponse != null ? aiResponse : "")
                    .createdAt(Instant.now())
                    .build();
            chatMessageRepository.save(aiMsg);

            ToolResultHolder.ToolResult toolResult = ToolResultHolder.get();

            DataPayLoad dataBuilder = DataPayLoad.builder()
                    .response(aiResponse)
                    .threadId(threadId)
                    .build();

            if (toolResult != null) {
                if (toolResult.bookingId() != null) {
                    dataBuilder.setBookingId(toolResult.bookingId());
                    dataBuilder.setRedirect(toolResult.redirect());
                }
                if (toolResult.stations() != null) {
                    dataBuilder.setStations(toolResult.stations());
                }
            }

            return AiChatResponse.builder()
                    .success(true)
                    .data(dataBuilder)
                    .build();

        } catch (Exception e) {
            log.error("Error in processVoiceChat", e);
            throw new RuntimeException("Failed to process message through Spring AI ChatClient", e);
        } finally {
            UserContextHolder.clear();
            ToolResultHolder.clear();
        }
    }
}
