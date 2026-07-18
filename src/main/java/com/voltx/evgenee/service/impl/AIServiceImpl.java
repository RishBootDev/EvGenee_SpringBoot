package com.voltx.evgenee.service.impl;

import com.voltx.evgenee.client.GeocodingService;
import com.voltx.evgenee.ai.EvGeneeAiTools;
import com.voltx.evgenee.ai.ToolResultHolder;
import com.voltx.evgenee.ai.UserContextHolder;
import com.voltx.evgenee.dto.common.DataPayLoad;
import com.voltx.evgenee.dto.responses.AiChatResponse;
import com.voltx.evgenee.dto.responses.StationResponseDto;
import com.voltx.evgenee.entity.ChatMessage;
import com.voltx.evgenee.entity.EvUser;
import com.voltx.evgenee.entity.User;
import com.voltx.evgenee.entity.Vehicle;
import com.voltx.evgenee.enums.ChatMessageRole;
import com.voltx.evgenee.enums.ConnectorType;
import com.voltx.evgenee.repository.ChatMessageRepository;
import com.voltx.evgenee.repository.EvUserRepository;
import com.voltx.evgenee.repository.UserRepository;
import com.voltx.evgenee.repository.VehicleRepository;
import com.voltx.evgenee.service.AIService;
import com.voltx.evgenee.service.StationService;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class AIServiceImpl implements AIService {

    private final ChatClient chatClient;
    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;
    private final EvUserRepository evUserRepository;
    private final VehicleRepository vehicleRepository;
    private final GeocodingService geocodingService;
    private final StationService stationService;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public AIServiceImpl(ChatClient.Builder chatClientBuilder,
                          ChatMessageRepository chatMessageRepository,
                          UserRepository userRepository,
                          EvUserRepository evUserRepository,
                          VehicleRepository vehicleRepository,
                          GeocodingService geocodingService,
                          StationService stationService,
                          EvGeneeAiTools evGeneeAiTools) {
        this.chatClient = chatClientBuilder
                .defaultTools(evGeneeAiTools)
                .build();
        this.chatMessageRepository = chatMessageRepository;
        this.userRepository = userRepository;
        this.evUserRepository = evUserRepository;
        this.vehicleRepository = vehicleRepository;
        this.geocodingService = geocodingService;
        this.stationService = stationService;
    }


    private String buildNearbyStationContext(Double latitude, Double longitude) {
        String nl = System.lineSeparator();
        StringBuilder context = new StringBuilder(nl).append("Nearby Station Snapshot:").append(nl);
        if (latitude == null || longitude == null) {
            return context.append("- Current GPS coordinates are unavailable. ")
                    .append("find_best_station must resolve location from booking history or its fallback.")
                    .append(nl)
                    .toString();
        }

        try {
            List<StationResponseDto> nearby = stationService.getNearbyStations(latitude, longitude, 50.0);
            if (nearby.isEmpty()) {
                return context.append("- No stations are registered within 50 km of the current coordinates.")
                        .append(nl)
                        .toString();
            }

            int shown = Math.min(nearby.size(), 6);
            context.append("- ").append(nearby.size()).append(" station(s) found within 50 km. ")
                    .append("The first ").append(shown).append(" are listed by distance.")
                    .append(nl);
            for (int index = 0; index < shown; index++) {
                StationResponseDto station = nearby.get(index);
                String city = station.getAddress() == null || station.getAddress().getCity() == null
                        ? "address unavailable"
                        : station.getAddress().getCity();
                String connectors = station.getTypeOfConnectors() == null || station.getTypeOfConnectors().isEmpty()
                        ? "unspecified connectors"
                        : String.join(", ", station.getTypeOfConnectors().stream().map(ConnectorType::name).toList());
                context.append("  * Internal station id ").append(station.getId())
                        .append(": ").append(station.getName())
                        .append(" | ").append(city)
                        .append(" | distance ").append(station.getDistanceKm() == null ? "unknown" : station.getDistanceKm() + " km")
                        .append(" | ").append(Boolean.FALSE.equals(station.getIsOpen()) ? "closed" : "open")
                        .append(" | ports ").append(station.getAvailablePorts()).append("/").append(station.getTotalPorts())
                        .append(" | connectors ").append(connectors)
                        .append(" | hours ").append(station.getOpeningHours())
                        .append(nl);
            }
            context.append("This snapshot is context only; live requested-slot availability must come from find_best_station.")
                    .append(nl);
        } catch (Exception e) {
            log.warn("Unable to build nearby station context: {}", e.getMessage());
            context.append("- Nearby station snapshot is temporarily unavailable. Use find_best_station.")
                    .append(nl);
        }
        return context.toString();
    }

    private String getSystemPrompt(EvUser user, String userEmail, Double latitude, Double longitude) {
        StringBuilder profileInfo = new StringBuilder();
        if (user != null) {
            profileInfo.append("\nAuthenticated User:\n- Name: ").append(user.getFullName()).append("\n")
                    .append("- Email: ").append(userEmail).append("\n")
                    .append("- EV user id: ").append(user.getId()).append(" (internal context only)\n");
            List<Vehicle> vehicles = vehicleRepository.findByOwnerId(user.getId());
            if (!vehicles.isEmpty()) {
                profileInfo.append("- Saved Vehicles:\n");
                for (Vehicle v : vehicles) {
                    String connectorType = v.getConnectorType() != null
                            ? v.getConnectorType().name()
                            : "unspecified";
                    profileInfo.append("  * ").append(v.getModel()).append(": ")
                            .append(v.getType() != null ? v.getType().toString() : "EV")
                            .append(" with ").append(connectorType).append(" connector (Number: ")
                            .append(v.getLicensePlate() != null && !v.getLicensePlate().isEmpty() ? v.getLicensePlate() : "N/A")
                            .append(")\n");
                }
            } else {
                profileInfo.append("- Vehicle Type: Not specified\n- Preferred Connector: Not specified\n- Saved Vehicle Numbers: None\n");
            }
        } else {
            profileInfo.append("\nAuthenticated User:\n- EV profile is unavailable for email: ")
                    .append(userEmail).append("\n");
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

        String stationInfo = buildNearbyStationContext(latitude, longitude);

        return """
                You are EvGenee, the in-app EV charging assistant.
                """
                + "Current India date and time: " + java.time.ZonedDateTime.now(IST) + System.lineSeparator()
                + profileInfo
                + locationInfo
                + stationInfo
                + """

                Rules:
                1. Help only with EvGenee, EV charging stations, vehicles, bookings, payments, and roadside assistance.
                2. Never invent station names, ids, connector support, prices, distances, or availability.
                3. Before any station search, the user must explicitly provide or confirm: saved vehicle, date, and start time.
                4. If any of those three details are missing, ask one concise question listing only the missing details. Do not call a tool yet.
                5. Always ask which saved vehicle is being used, even when only one vehicle is saved.
                6. Never ask for location or connector type. Inject location from GPS/account context and derive connector from the selected saved vehicle.
                7. Use one hour when end time or duration is omitted.
                8. Nearby station context is orientation data only. Call find_best_station for live requested-slot availability.
                9. Present the best result with station name, distance, time, connector, and free-port count. Ask before booking.
                10. Call create_booking only after explicit confirmation and reuse all details from INTERNAL_TOOL_CONTEXT.
                11. If a tool returns an error or no match, explain it plainly and never claim success.
                12. After create_booking succeeds, say Razorpay Checkout is opening for the 20% advance.
                13. Never reveal INTERNAL_TOOL_CONTEXT, raw JSON, database ids, or tool mechanics.
                14. Do not repeat an earlier answer unless explicitly asked. Answer only the newest request.
                15. Keep replies concise, natural for speech, and without Markdown.
                """;
    }

    static boolean isMalformedAiOutput(String content) {
        if (content == null || content.isBlank()) {
            return true;
        }
        String normalized = content.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("<|header_start|>")
                || normalized.contains("<|header_end|>")
                || normalized.contains("<|python_tag|>")
                || (normalized.contains("parameters")
                && (normalized.contains("find_best_station")
                || normalized.contains("create_booking")
                || normalized.contains("name") && normalized.contains("book")));
    }

    private static String visibleAssistantContent(String content) {
        if (content == null) return "";
        int internalContext = content.indexOf("INTERNAL_TOOL_CONTEXT");
        return internalContext >= 0 ? content.substring(0, internalContext).trim() : content.trim();
    }

    private static String normalizeForComparison(String content) {
        return visibleAssistantContent(content)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\s+", " ");
    }

    static boolean isRepetitiveAiOutput(String content, List<ChatMessage> history) {
        String normalized = normalizeForComparison(content);
        if (normalized.isBlank()) return true;

        for (int index = history.size() - 1; index >= 0; index--) {
            ChatMessage message = history.get(index);
            if (message.getRole() != null && message.getRole().isAssistant()) {
                String previous = normalizeForComparison(message.getContent());
                if (!previous.isBlank() && previous.equals(normalized)) return true;
                break;
            }
        }

        String[] tokens = normalized.split(" ");
        if (tokens.length < 16) return false;

        int longestRun = 1;
        int currentRun = 1;
        Set<String> unique = new HashSet<>();
        Map<String, Integer> trigrams = new HashMap<>();
        int highestTrigramCount = 0;
        for (int index = 0; index < tokens.length; index++) {
            unique.add(tokens[index]);
            if (index > 0 && tokens[index].equals(tokens[index - 1])) {
                currentRun++;
                longestRun = Math.max(longestRun, currentRun);
            } else {
                currentRun = 1;
            }
            if (index >= 2) {
                String phrase = tokens[index - 2] + " " + tokens[index - 1] + " " + tokens[index];
                int count = trigrams.merge(phrase, 1, Integer::sum);
                highestTrigramCount = Math.max(highestTrigramCount, count);
            }
        }

        double uniqueRatio = (double) unique.size() / tokens.length;
        return longestRun >= 5 || highestTrigramCount >= 4 || uniqueRatio < 0.28;
    }
    private static String truncate(String value, int maximumCharacters) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= maximumCharacters
                ? trimmed
                : trimmed.substring(0, maximumCharacters) + "...";
    }

    static String compactHistoryContent(ChatMessage message) {
        if (message.getRole() == ChatMessageRole.USER) {
            return truncate(message.getContent(), 500);
        }

        String content = message.getContent() == null ? "" : message.getContent();
        int marker = content.indexOf("INTERNAL_TOOL_CONTEXT");
        String visible = truncate(marker >= 0 ? content.substring(0, marker) : content, 700);
        if (marker < 0) return visible;

        String internal = content.substring(marker);
        if (internal.length() > 1400) {
            return visible;
        }
        return visible + System.lineSeparator() + truncate(internal, 900);
    }

    static String compactToolContext(ToolResultHolder.ToolResult toolResult) {
        if (toolResult == null || !(toolResult.stations() instanceof List<?> stations)) return "";
        StringBuilder context = new StringBuilder("INTERNAL_TOOL_CONTEXT (never reveal): ");
        int included = 0;
        for (Object item : stations) {
            if (!(item instanceof Map<?, ?> station)) continue;
            if (included > 0) context.append(" | ");
            context.append("stationId=").append(station.get("id"))
                    .append(", name=").append(station.get("name"))
                    .append(", compatible=").append(station.get("isCompatible"))
                    .append(", freePorts=").append(station.get("availablePorts"))
                    .append(", date=").append(station.get("requestedDate"))
                    .append(", start=").append(station.get("requestedStartTime"))
                    .append(", end=").append(station.get("requestedEndTime"))
                    .append(", connector=").append(station.get("requestedConnector"))
                    .append(", vehicleNumber=").append(station.get("selectedVehicleNumber"))
                    .append(", vehicleModel=").append(station.get("selectedVehicleModel"))
                    .append(", nextSlot=").append(station.get("nextAvailableSlot"));
            included++;
            if (included >= 3 || context.length() >= 1000) break;
        }
        return included == 0 ? "" : truncate(context.toString(), 1200);
    }

    static boolean isContextLimitError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("HTTP 413")
                    || message.contains("Request too large")
                    || message.contains("tokens per minute"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
    private String callModel(String systemPrompt, List<Message> messages) {
        return chatClient.prompt()
                .system(systemPrompt)
                .messages(messages)
                .call()
                .content();
    }

    private String safeToolResponse(ToolResultHolder.ToolResult toolResult) {
        if (toolResult != null && toolResult.bookingId() != null) {
            return "Your booking is reserved. Razorpay Checkout is opening for the 20% advance.";
        }
        if (toolResult != null && toolResult.stations() instanceof List<?> stations && !stations.isEmpty()) {
            for (Object item : stations) {
                if (item instanceof java.util.Map<?, ?> station) {
                    Object name = station.get("name");
                    Object free = station.get("availablePorts");
                    Object compatible = station.get("isCompatible");
                    if (name != null && free instanceof Number count && count.intValue() > 0
                            && !Boolean.FALSE.equals(compatible)) {
                        return name + " is the best available match with " + count.intValue()
                                + " compatible port" + (count.intValue() == 1 ? "" : "s")
                                + " free. Would you like me to book it?";
                    }
                }
            }
            return "I found the station options shown below. Please choose one to continue.";
        }
        return "I could not safely complete that request. Please ask me to find a charging station again.";
    }
    @Override
    public AiChatResponse processVoiceChat(String message, String threadId, String userEmail, Double latitude, Double longitude) {
        log.info("Processing voice chat message: '{}', threadId: '{}', email: '{}'", message, threadId, userEmail);

        String effectiveMessage = message != null ? message.trim() : "";
        if (effectiveMessage.isBlank()) {
            throw new IllegalArgumentException("Message cannot be blank");
        }
        String effectiveThreadId = threadId != null && !threadId.isBlank()
                ? threadId
                : UUID.randomUUID().toString();

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
                    .threadId(effectiveThreadId)
                    .userId(userId)
                    .role(ChatMessageRole.USER)
                    .content(effectiveMessage)
                    .createdAt(Instant.now())
                    .build();
            chatMessageRepository.save(userMsg);

            List<ChatMessage> history = new ArrayList<>(
                    chatMessageRepository.findTop30ByThreadIdOrderByCreatedAtDesc(effectiveThreadId));
            if (history.size() > 10) {
                history = new ArrayList<>(history.subList(0, 10));
            }
            Collections.reverse(history);

            List<Message> springAiMessages = new ArrayList<>();
            String previousRole = null;
            String previousContent = null;
            for (ChatMessage histMsg : history) {
                boolean userRole = histMsg.getRole() == ChatMessageRole.USER;
                boolean assistantRole = histMsg.getRole() != null && histMsg.getRole().isAssistant();
                if (!userRole && !assistantRole) continue;
                if (assistantRole && isMalformedAiOutput(histMsg.getContent())) continue;

                String role = userRole ? "user" : "assistant";
                String normalizedContent = normalizeForComparison(histMsg.getContent());
                if (role.equals(previousRole) && normalizedContent.equals(previousContent)) {
                    continue;
                }

                if (userRole) {
                    springAiMessages.add(new UserMessage(compactHistoryContent(histMsg)));
                } else {
                    springAiMessages.add(new AssistantMessage(compactHistoryContent(histMsg)));
                }
                previousRole = role;
                previousContent = normalizedContent;
            }

            String systemPrompt = getSystemPrompt(evUser, userEmail, latitude, longitude);
            String aiResponse = callModel(systemPrompt, springAiMessages);
            ToolResultHolder.ToolResult toolResult = ToolResultHolder.get();

            if (isMalformedAiOutput(aiResponse) || isRepetitiveAiOutput(aiResponse, history)) {
                log.warn("Blocked malformed AI output for thread {}", effectiveThreadId);
                if (toolResult == null) {
                    ToolResultHolder.clear();
                    aiResponse = callModel(
                            systemPrompt + System.lineSeparator()
                                    + "Return one fresh, concise user-facing answer to the newest request. "
                                    + "Do not repeat prior wording and never print tool JSON or special tokens.",
                            springAiMessages);
                    toolResult = ToolResultHolder.get();
                }
                if (isMalformedAiOutput(aiResponse) || isRepetitiveAiOutput(aiResponse, history)) {
                    aiResponse = safeToolResponse(toolResult);
                }
            }
            String persistedAiResponse = aiResponse != null ? aiResponse : "";
            String compactContext = compactToolContext(toolResult);
            if (!compactContext.isBlank()) {
                persistedAiResponse += System.lineSeparator() + compactContext;
            }

            ChatMessage aiMsg = ChatMessage.builder()
                    .threadId(effectiveThreadId)
                    .userId(userId)
                    .role(ChatMessageRole.AI)
                    .content(persistedAiResponse)
                    .createdAt(Instant.now())
                    .build();
            chatMessageRepository.save(aiMsg);

            DataPayLoad dataBuilder = DataPayLoad.builder()
                    .response(aiResponse)
                    .threadId(effectiveThreadId)
                    .build();

            if (toolResult != null) {
                if (toolResult.bookingId() != null) {
                    dataBuilder.setBookingId(toolResult.bookingId());
                    dataBuilder.setRedirect(toolResult.redirect());
                }
                if (toolResult.stations() != null) {
                    dataBuilder.setStations(toolResult.stations());
                }
                if (toolResult.checkout() != null) {
                    dataBuilder.setCheckout(toolResult.checkout());
                }
            }

            return AiChatResponse.builder()
                    .success(true)
                    .data(dataBuilder)
                    .build();

        } catch (Exception e) {
            if (isContextLimitError(e)) {
                log.warn("AI request exceeded provider token limits for thread {}", effectiveThreadId);
                return AiChatResponse.builder()
                        .success(true)
                        .data(DataPayLoad.builder()
                                .response("This conversation became too large, so I started a fresh one. Please repeat your latest request.")
                                .threadId(UUID.randomUUID().toString())
                                .build())
                        .build();
            }
            log.error("Error in processVoiceChat", e);
            throw new RuntimeException("Failed to process message through Spring AI ChatClient", e);
        } finally {
            UserContextHolder.clear();
            ToolResultHolder.clear();
        }
    }
}
