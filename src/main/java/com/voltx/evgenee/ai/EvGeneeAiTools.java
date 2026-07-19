package com.voltx.evgenee.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltx.evgenee.client.GeocodingService;
import com.voltx.evgenee.dto.common.PricingDto;
import com.voltx.evgenee.dto.requests.PaymentRequestDto;
import com.voltx.evgenee.dto.responses.PaymentResponseDto;
import com.voltx.evgenee.dto.responses.RazorpayCheckoutDto;
import com.voltx.evgenee.entity.*;
import com.voltx.evgenee.enums.BookingStatus;
import com.voltx.evgenee.enums.ConnectorType;
import com.voltx.evgenee.enums.CurrencyCode;
import com.voltx.evgenee.enums.StationApprovalStatus;
import com.voltx.evgenee.enums.StationStatus;
import com.voltx.evgenee.repository.*;
import com.voltx.evgenee.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

@Component
@Slf4j
@RequiredArgsConstructor
public class EvGeneeAiTools {

    private final StationRepository stationRepository;
    private final BookingRepository bookingRepository;
    private final EvUserRepository evUserRepository;
    private final VehicleRepository vehicleRepository;
    private final GeocodingService geocodingService;
    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    private static final double BhopalLon = 77.4126;
    private static final double BhopalLat = 23.2599;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static String minutesToTime(int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        return String.format("%02d:%02d", h, m);
    }

    private static int timeToMinutes(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static LocalDate resolveDate(String value) {
        if (!hasText(value) || value.equalsIgnoreCase("today")) {
            return LocalDate.now(IST);
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception ignored) {
            return LocalDate.now(IST);
        }
    }

    private static String resolveStartTime(String startTime, LocalDate queryDate) {
        if (hasText(startTime)) {
            return startTime.trim();
        }

        if (queryDate.equals(LocalDate.now(IST))) {
            LocalTime now = LocalTime.now(IST).plusMinutes(30);
            int minutes = now.getHour() * 60 + now.getMinute();
            int rounded = ((minutes + 14) / 15) * 15;
            if (rounded >= 22 * 60) {
                return "08:00";
            }
            return minutesToTime(rounded);
        }
        return "08:00";
    }

    private static String resolveEndTime(String endTime, String startTime) {
        if (hasText(endTime)) {
            return endTime.trim();
        }
        return minutesToTime(timeToMinutes(startTime) + 60);
    }

    private Vehicle resolveSelectedVehicle(EvUser user, String selection) {
        if (!hasText(selection)) {
            throw new IllegalArgumentException("Ask the user which saved vehicle they are using.");
        }
        List<Vehicle> vehicles = vehicleRepository.findByOwnerId(user.getId());
        if (vehicles.isEmpty()) {
            throw new IllegalArgumentException("No saved vehicle was found. Add a vehicle to the profile first.");
        }
        String requested = selection.trim();
        return vehicles.stream()
                .filter(vehicle -> (vehicle.getLicensePlate() != null
                        && vehicle.getLicensePlate().equalsIgnoreCase(requested))
                        || (vehicle.getModel() != null
                        && vehicle.getModel().equalsIgnoreCase(requested)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "That vehicle is not saved in the logged-in user's profile."));
    }

    private String connectorFor(Vehicle vehicle) {
        if (vehicle.getConnectorType() == null) {
            throw new IllegalArgumentException("The selected vehicle has no connector type configured.");
        }
        return vehicle.getConnectorType().name();
    }

    private List<Booking> activeBookingsForStation(Long stationId, LocalDate date, String connectorType) {
        Instant dayStart = date.atStartOfDay(IST).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(IST).toInstant();
        return bookingRepository.findStationBookingsForDay(stationId, dayEnd, dayStart).stream()
                .filter(this::isActiveForAvailability)
                .filter(booking -> sameConnector(booking.getConnectorType(), connectorType))
                .toList();
    }
    private boolean isActiveForAvailability(Booking booking) {
        if (booking.getStatus() == BookingStatus.CONFIRMED || booking.getStatus() == BookingStatus.IN_PROGRESS) {
            return true;
        }
        return booking.getStatus() == BookingStatus.PENDING
                && booking.getCreatedAt() != null
                && booking.getCreatedAt().isAfter(Instant.now().minusSeconds(600));
    }

    private static boolean isOverlapping(String startA, String endA, String startB, String endB) {
        return timeToMinutes(startA) < timeToMinutes(endB) && timeToMinutes(startB) < timeToMinutes(endA);
    }

    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return 6371 * c;
    }

    private AvailabilityResult checkAvailability(Long stationId, LocalDate date, String startTime, String endTime, int maxPorts, List<Booking> bookings) {
        int reqStart = timeToMinutes(startTime);
        int reqEnd = timeToMinutes(endTime);

        List<Event> events = new ArrayList<>();
        int currentConcurrent = 0;

        for (Booking b : bookings) {
            ZonedDateTime startZ = b.getStartTime().atZone(IST);
            ZonedDateTime endZ = b.getEndTime().atZone(IST);
            if (!startZ.toLocalDate().equals(date)) continue;

            String bStartStr = String.format("%02d:%02d", startZ.getHour(), startZ.getMinute());
            String bEndStr = String.format("%02d:%02d", endZ.getHour(), endZ.getMinute());

            int bStart = timeToMinutes(bStartStr);
            int bEnd = timeToMinutes(bEndStr);

            if (bStart < reqEnd && bEnd > reqStart) {
                events.add(new Event(bStart, 1));
                events.add(new Event(bEnd, -1));
            }
            if (reqStart >= bStart && reqStart < bEnd) {
                currentConcurrent++;
            }
        }

        if (currentConcurrent >= maxPorts) {
            return new AvailabilityResult(false, startTime);
        }

        events.sort(Comparator.comparingInt((Event e) -> e.time).thenComparingInt(e -> e.type));

        for (Event event : events) {
            if (event.time >= reqEnd) break;
            if (event.time > reqStart) {
                currentConcurrent += event.type;
                if (currentConcurrent >= maxPorts) {
                    return new AvailabilityResult(false, minutesToTime(event.time));
                }
            }
        }

        return new AvailabilityResult(true, null);
    }

    private int availablePortCount(
            LocalDate date,
            String startTime,
            String endTime,
            int capacity,
            List<Booking> bookings) {
        int requestStart = timeToMinutes(startTime);
        int requestEnd = timeToMinutes(endTime);
        List<Event> events = new ArrayList<>();
        for (Booking booking : bookings) {
            if (booking.getStartTime() == null || booking.getEndTime() == null) continue;
            ZonedDateTime start = booking.getStartTime().atZone(IST);
            ZonedDateTime end = booking.getEndTime().atZone(IST);
            if (!start.toLocalDate().equals(date)) continue;
            int bookingStart = start.getHour() * 60 + start.getMinute();
            int bookingEnd = end.getHour() * 60 + end.getMinute();
            if (bookingStart < requestEnd && bookingEnd > requestStart) {
                events.add(new Event(Math.max(requestStart, bookingStart), 1));
                events.add(new Event(Math.min(requestEnd, bookingEnd), -1));
            }
        }
        events.sort(Comparator.comparingInt(Event::time).thenComparingInt(Event::type));
        int occupied = 0;
        int maximumOccupied = 0;
        for (Event event : events) {
            occupied += event.type();
            maximumOccupied = Math.max(maximumOccupied, occupied);
        }
        return Math.max(0, capacity - maximumOccupied);
    }

    private boolean isWithinOpeningHours(Station station, String startTime, String endTime) {
        String openingHours = hasText(station.getOpeningHours()) ? station.getOpeningHours() : "08:00 - 22:00";
        if ("24/7".equalsIgnoreCase(openingHours.trim())) return true;
        try {
            String[] bounds = openingHours.split("\s*-\s*");
            if (bounds.length != 2) return true;
            int opening = timeToMinutes(bounds[0]);
            int closing = timeToMinutes(bounds[1]);
            return timeToMinutes(startTime) >= opening && timeToMinutes(endTime) <= closing;
        } catch (Exception ignored) {
            return true;
        }
    }
    private String findNextAvailableSlot(Long stationId, LocalDate date, String startTime, int durationMinutes, int maxPorts, String openingHours, List<Booking> bookings) {
        int currentStartMin = timeToMinutes(startTime);
        int searchLimitMin = currentStartMin + 480;
        int openMin = 0;
        int closeMin = 1439;

        if (openingHours != null && openingHours.contains("-")) {
            try {
                String[] parts = openingHours.split("-");
                openMin = timeToMinutes(parts[0].trim());
                closeMin = timeToMinutes(parts[1].trim());
            } catch (Exception ignored) {}
        }

        List<Interval> bookingIntervals = new ArrayList<>();
        for (Booking b : bookings) {
            ZonedDateTime startZ = b.getStartTime().atZone(IST);
            ZonedDateTime endZ = b.getEndTime().atZone(IST);
            if (!startZ.toLocalDate().equals(date)) continue;

            String bStartStr = String.format("%02d:%02d", startZ.getHour(), startZ.getMinute());
            String bEndStr = String.format("%02d:%02d", endZ.getHour(), endZ.getMinute());

            bookingIntervals.add(new Interval(timeToMinutes(bStartStr), timeToMinutes(bEndStr)));
        }

        while (currentStartMin + durationMinutes <= Math.min(searchLimitMin, closeMin)) {
            currentStartMin += 15;
            int nextStart = currentStartMin;
            int nextEnd = currentStartMin + durationMinutes;

            int concurrent = 0;
            for (Interval interval : bookingIntervals) {
                if (interval.start < nextEnd && interval.end > nextStart) {
                    concurrent++;
                    if (concurrent >= maxPorts) break;
                }
            }

            if (concurrent < maxPorts) {
                return minutesToTime(nextStart);
            }
        }

        return null;
    }

    private record Event(int time, int type) {}
    private record Interval(int start, int end) {}
    private record AvailabilityResult(boolean available, String conflictTime) {}

    private List<PricingDto> stationPricing(Station station) {
        int ports = station.getChargersCount() != null ? station.getChargersCount() : 4;
        List<PricingDto> fallback = List.of(
                PricingDto.builder().connectorType(ConnectorType.CCS2).priceperKWh(15.0)
                        .portCount(Math.max(1, ports / 2)).currency(CurrencyCode.INR).build(),
                PricingDto.builder().connectorType(ConnectorType.TYPE2).priceperKWh(12.0)
                        .portCount(Math.max(1, ports / 2)).currency(CurrencyCode.INR).build());
        if (station.getPricing() == null || station.getPricing().isEmpty()) return fallback;
        return station.getPricing().stream()
                .map(item -> PricingDto.builder()
                        .connectorType(item.getConnectorType())
                        .priceperKWh(item.getPriceperKWh())
                        .portCount(item.getPortCount())
                        .currency(item.getCurrency())
                        .build())
                .toList();
    }

    private List<String> stationConnectors(Station station, List<PricingDto> pricing) {
        List<String> fallback = pricing.stream()
                .map(PricingDto::getConnectorType)
                .map(EvGeneeAiTools::connectorValue)
                .filter(EvGeneeAiTools::hasText)
                .distinct()
                .toList();
        if (station.getConnectors() == null || station.getConnectors().isEmpty()) return fallback;
        return station.getConnectors().stream()
                .map(StationConnector::getConnectorType)
                .map(EvGeneeAiTools::connectorValue)
                .filter(EvGeneeAiTools::hasText)
                .distinct()
                .toList();
    }

    private boolean sameConnector(Object first, Object second) {
        String left = connectorValue(first).replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        String right = connectorValue(second).replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        return left.equals(right);
    }

    private static String connectorValue(Object value) {
        if (value == null) return "";
        if (value instanceof ConnectorType connectorType) return connectorType.name();
        return String.valueOf(value);
    }

    private int connectorCapacity(Station station, String connector, List<String> connectors, List<PricingDto> pricing) {
        int total = station.getChargersCount() != null ? station.getChargersCount() : 4;
        return pricing.stream()
                .filter(item -> sameConnector(item.getConnectorType(), connector))
                .map(PricingDto::getPortCount)
                .filter(Objects::nonNull)
                .filter(count -> count > 0)
                .findFirst()
                .map(count -> Math.min(count, total))
                .orElse(Math.max(1, total / Math.max(1, connectors.size())));
    }

    private Map<String, Object> mapStationToDto(
            Station st,
            Double distKm,
            Double travelTimeMins,
            String requestedConnector,
            int availablePorts) {
        List<PricingDto> pricing = stationPricing(st);
        List<String> connectors = stationConnectors(st, pricing);
        boolean compatible = connectors.stream().anyMatch(type -> sameConnector(type, requestedConnector));

        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", st.getId().toString());
        dto.put("_id", st.getId().toString());
        dto.put("name", st.getName());
        dto.put("city", st.getAddress() == null ? "Bhopal" : st.getAddress().split(",")[0].trim());
        dto.put("address", st.getAddress());
        dto.put("isOpen", st.getOpen() == null || st.getOpen());
        dto.put("isCompatible", compatible);
        dto.put("totalPorts", st.getChargersCount() != null ? st.getChargersCount() : 4);
        dto.put("availablePorts", Math.max(0, availablePorts));
        dto.put("chargerTypes", connectors);
        dto.put("typeOfConnectors", connectors);
        dto.put("chargingSpeed", st.getChargingSpeed() != null ? st.getChargingSpeed() : 50);
        dto.put("pricing", pricing);
        dto.put("openingHours", st.getOpeningHours() != null ? st.getOpeningHours() : "08:00 - 22:00");
        dto.put("roadDistance", distKm);
        dto.put("travelTime", travelTimeMins);
        dto.put("location", Map.of(
                "lat", st.getLatitude() != null ? st.getLatitude() : BhopalLat,
                "lng", st.getLongitude() != null ? st.getLongitude() : BhopalLon));
        return dto;
    }

    private void addStationResult(List<Map<String, Object>> results, Map<String, Object> station, boolean selected) {
        if (selected && results.size() >= 5) {
            results.remove(results.size() - 1);
        }
        if (results.size() < 5) {
            results.add(station);
        }
    }
    public record FindBestStationInput(
        String date,
        String startTime,
        String endTime,
        String vehicleNumber
    ) {}

    @Tool(name = "find_best_station", description = "Finds real EV stations by connector compatibility, requested-slot availability, opening hours, and distance. Always call this before offering a station.")
    public String findBestStation(
            @ToolParam(description = "Booking date in yyyy-MM-dd format.") String date,
            @ToolParam(description = "Booking start time in HH:mm format.") String startTime,
            @ToolParam(required = false, description = "Optional booking end time in HH:mm format. Defaults to one hour after startTime.") String endTime,
            @ToolParam(description = "License plate or model of the saved vehicle selected by the user.") String vehicleNumber) {
        return findBestStation(new FindBestStationInput(date, startTime, endTime, vehicleNumber));
    }

    String findBestStation(FindBestStationInput input) {
            try {
                log.info("Tool find_best_station called with input: {}", input);
                UserContextHolder.UserContext uCtx = UserContextHolder.get();
                String userEmail = uCtx != null ? uCtx.email() : null;
                Double userLat = uCtx != null ? uCtx.lat() : null;
                Double userLng = uCtx != null ? uCtx.lng() : null;
                if (!hasText(userEmail)) {
                    return error("The user must be logged in before searching.");
                }
                if (input == null || !hasText(input.vehicleNumber())
                        || !hasText(input.date()) || !hasText(input.startTime())) {
                    return error("Ask the user for their saved vehicle, date, and start time before searching.");
                }

                EvUser currentUser = evUserRepository.findByEmail(userEmail)
                        .orElseThrow(() -> new IllegalArgumentException("EV user profile not found."));
                Vehicle selectedVehicle = resolveSelectedVehicle(currentUser, input.vehicleNumber());
                String effectiveConnectorType = connectorFor(selectedVehicle);

                double[] coords = null;
                String locationName = null;
                if (coords == null && userLat != null && userLng != null) {
                    coords = new double[]{userLng, userLat};
                    String addr = geocodingService.reverseGeocode(userLat, userLng);
                    locationName = addr != null ? addr : "your current location";
                }

                if (coords == null) {
                    Booking last = bookingRepository
                            .findFirstByUserIdOrderByCreatedAtDesc(currentUser.getId())
                            .orElse(null);
                    if (last != null && last.getStation() != null) {
                        Station st = last.getStation();
                        if (st.getLatitude() != null && st.getLongitude() != null) {
                            coords = new double[]{st.getLongitude(), st.getLatitude()};
                            locationName = st.getName();
                        }
                    }
                }

                if (coords == null) {
                    coords = new double[]{BhopalLon, BhopalLat};
                    locationName = "Bhopal";
                }

                List<Station> stations = new ArrayList<>(
                        stationRepository.findByApprovalStatusAndLatitudeIsNotNullAndLongitudeIsNotNullOrderByIdDesc(
                                StationApprovalStatus.APPROVED));
                final double[] finalCoords = coords;
                stations.sort(Comparator.comparingDouble(s -> calculateDistance(finalCoords[1], finalCoords[0], s.getLatitude() != null ? s.getLatitude() : BhopalLat, s.getLongitude() != null ? s.getLongitude() : BhopalLon)));
                if (stations.isEmpty()) {
                    return "{\"error\": \"I couldn't find any charging stations nearby.\" }";
                }

                LocalDate queryDate = resolveDate(input != null ? input.date() : null);
                String effectiveStartTime = resolveStartTime(input != null ? input.startTime() : null, queryDate);
                String effectiveEndTime = resolveEndTime(input != null ? input.endTime() : null, effectiveStartTime);

                LocalDate today = LocalDate.now(IST);
                LocalTime nowTime = LocalTime.now(IST);
                int currentMinutes = nowTime.getHour() * 60 + nowTime.getMinute();
                boolean missingStartTime = input == null || !hasText(input.startTime());
                if (missingStartTime && queryDate.equals(today) && timeToMinutes(effectiveStartTime) <= currentMinutes) {
                    queryDate = queryDate.plusDays(1);
                }

                if (queryDate.isBefore(today)) {
                    return "{\"error\": \"Cannot search for past dates.\" }";
                }
                if (queryDate.equals(today) && timeToMinutes(effectiveStartTime) <= currentMinutes) {
                    return "{\"error\": \"The requested start time has already passed for today. Please provide a future time.\" }";
                }

                Station exactMatchStation = null;
                GeocodingService.RoadInfo exactRoadInfo = null;
                List<Map<String, Object>> stationsData = new ArrayList<>();

                int reqDuration = timeToMinutes(effectiveEndTime) - timeToMinutes(effectiveStartTime);
                if (reqDuration < 60) {
                    return error("The charging window must be at least one hour and end after it starts.");
                }

                int limit = stations.size();
                for (int i = 0; i < limit; i++) {
                    Station st = stations.get(i);
                    double dist = calculateDistance(finalCoords[1], finalCoords[0], st.getLatitude() != null ? st.getLatitude() : BhopalLat, st.getLongitude() != null ? st.getLongitude() : BhopalLon);
                    GeocodingService.RoadInfo roadInfo = geocodingService.getRoadDistance(finalCoords, new double[]{st.getLongitude() != null ? st.getLongitude() : BhopalLon, st.getLatitude() != null ? st.getLatitude() : BhopalLat});
                    
                    double roadDist = roadInfo != null ? roadInfo.distanceKm() : dist;
                    double roadTime = roadInfo != null ? roadInfo.durationMins() : (dist * 1.5);
                    List<PricingDto> stationPricing = stationPricing(st);
                    List<String> stationConnectors = stationConnectors(st, stationPricing);
                    boolean compatible = stationConnectors.stream()
                            .anyMatch(type -> sameConnector(type, effectiveConnectorType));
                    int connectorPorts = compatible
                            ? connectorCapacity(st, effectiveConnectorType, stationConnectors, stationPricing)
                            : 0;
                    Map<String, Object> sDto = mapStationToDto(
                            st, roadDist, roadTime, effectiveConnectorType, connectorPorts);
                    sDto.put("selectedVehicleNumber", selectedVehicle.getLicensePlate());
                    sDto.put("selectedVehicleModel", selectedVehicle.getModel());

                    if (!compatible) {
                        sDto.put("availablePorts", 0);
                        sDto.put("nextAvailableSlot", null);
                        addStationResult(stationsData, sDto, false);
                        continue;
                    }

                    if (Boolean.FALSE.equals(st.getOpen()) || st.getStatus() == StationStatus.INACTIVE) {
                        sDto.put("nextAvailableSlot", null);
                        addStationResult(stationsData, sDto, false);
                        continue;
                    }

                    List<Booking> activeBookings = activeBookingsForStation(
                            st.getId(), queryDate, effectiveConnectorType);

                    int maxPorts = connectorPorts;
                    AvailabilityResult avResult = checkAvailability(st.getId(), queryDate, effectiveStartTime, effectiveEndTime, maxPorts, activeBookings);

                    int freePorts = isWithinOpeningHours(st, effectiveStartTime, effectiveEndTime)
                            ? availablePortCount(queryDate, effectiveStartTime, effectiveEndTime, maxPorts, activeBookings)
                            : 0;
                    sDto.put("availablePorts", freePorts);
                    sDto.put("requestedDate", queryDate.toString());
                    sDto.put("requestedStartTime", effectiveStartTime);
                    sDto.put("requestedEndTime", effectiveEndTime);
                    avResult = new AvailabilityResult(avResult.available() && freePorts > 0, avResult.conflictTime());
                    if (avResult.available()) {
                        exactMatchStation = st;
                        exactRoadInfo = roadInfo;
                        sDto.put("nextAvailableSlot", effectiveStartTime);
                        addStationResult(stationsData, sDto, true);
                        break;
                    } else {
                        String nextSlot = findNextAvailableSlot(st.getId(), queryDate, effectiveStartTime, reqDuration, maxPorts,
                                hasText(st.getOpeningHours()) ? st.getOpeningHours() : "08:00 - 22:00", activeBookings);
                        sDto.put("nextAvailableSlot", nextSlot);
                        addStationResult(stationsData, sDto, false);
                    }
                }

                ObjectMapper mapper = objectMapper;
                if (exactMatchStation != null) {
                    double distKm = exactRoadInfo != null ? exactRoadInfo.distanceKm() : calculateDistance(
                            finalCoords[1],
                            finalCoords[0],
                            exactMatchStation.getLatitude() != null ? exactMatchStation.getLatitude() : BhopalLat,
                            exactMatchStation.getLongitude() != null ? exactMatchStation.getLongitude() : BhopalLon);
                    double timeMin = exactRoadInfo != null ? exactRoadInfo.durationMins() : distKm * 1.5;
                    String distStr = String.format(" (approx. %.2f KM, %.0f mins away by road)", distKm, timeMin);
                    
                    Map<String, Object> resp = new HashMap<>();
                    resp.put("text", "Found a great match! " + exactMatchStation.getName() + distStr + " is AVAILABLE from " + effectiveStartTime + " to " + effectiveEndTime + ".\nWould you like me to book it for you?");
                    resp.put("stations", stationsData);
                    resp.put("foundAvailable", true);
                    resp.put("selectedStationId", exactMatchStation.getId().toString());
                    resp.put("selectedStationName", exactMatchStation.getName());
                    resp.put("date", queryDate.toString());
                    resp.put("startTime", effectiveStartTime);
                    resp.put("endTime", effectiveEndTime);
                    resp.put("connectorType", effectiveConnectorType);
                    ToolResultHolder.set(new ToolResultHolder.ToolResult(null, null, stationsData));
                    return mapper.writeValueAsString(resp);
                }

                Station altMatchStation = null;
                String altStartStr = null;
                String altEndStr = null;
                GeocodingService.RoadInfo altRoadInfo = null;

                for (int i = 0; i < limit; i++) {
                    Station st = stations.get(i);
                    if (Boolean.FALSE.equals(st.getOpen()) || st.getStatus() == StationStatus.INACTIVE) {
                        continue;
                    }
                    List<PricingDto> altPricing = stationPricing(st);
                    List<String> altConnectors = stationConnectors(st, altPricing);
                    if (altConnectors.stream().noneMatch(type -> sameConnector(type, effectiveConnectorType))) {
                        continue;
                    }
                    int maxPorts = connectorCapacity(st, effectiveConnectorType, altConnectors, altPricing);

                    List<Booking> activeBookings = activeBookingsForStation(
                            st.getId(), queryDate, effectiveConnectorType);

                    int startMins = timeToMinutes(effectiveStartTime);
                    for (int offset = 60; offset <= 240; offset += 60) {
                        int altStart = startMins + offset;
                        int altEnd = altStart + reqDuration;
                        if (altStart >= 24 * 60 || altEnd >= 24 * 60) continue;

                        String ast = minutesToTime(altStart);
                        String aed = minutesToTime(altEnd);

                        if (!isWithinOpeningHours(st, ast, aed)) continue;
                        AvailabilityResult altAv = checkAvailability(st.getId(), queryDate, ast, aed, maxPorts, activeBookings);
                        if (altAv.available()) {
                            altMatchStation = st;
                            altStartStr = ast;
                            altEndStr = aed;
                            altRoadInfo = geocodingService.getRoadDistance(finalCoords, new double[]{
                                    st.getLongitude() != null ? st.getLongitude() : BhopalLon,
                                    st.getLatitude() != null ? st.getLatitude() : BhopalLat});
                            break;
                        }
                    }
                    if (altMatchStation != null) break;
                }

                if (altMatchStation != null) {
                    double distKm = altRoadInfo != null ? altRoadInfo.distanceKm() : calculateDistance(
                            finalCoords[1], finalCoords[0],
                            altMatchStation.getLatitude() != null ? altMatchStation.getLatitude() : BhopalLat,
                            altMatchStation.getLongitude() != null ? altMatchStation.getLongitude() : BhopalLon);
                    double timeMin = altRoadInfo != null ? altRoadInfo.durationMins() : distKm * 1.5;
                    String distStr = String.format(" (approx. %.2f KM, %.0f mins away)", distKm, timeMin);

                    Map<String, Object> resp = new HashMap<>();
                    resp.put("text", "The requested time slot is fully booked at nearby stations. However, " + altMatchStation.getName() + distStr + " is AVAILABLE later from " + altStartStr + " to " + altEndStr + ".\nWould you like to book this alternative slot instead?");
                    resp.put("stations", stationsData);
                    resp.put("foundAvailable", true);
                    resp.put("selectedStationId", altMatchStation.getId().toString());
                    resp.put("selectedStationName", altMatchStation.getName());
                    resp.put("date", queryDate.toString());
                    resp.put("startTime", altStartStr);
                    resp.put("endTime", altEndStr);
                    resp.put("connectorType", effectiveConnectorType);
                    ToolResultHolder.set(new ToolResultHolder.ToolResult(null, null, stationsData));
                    return mapper.writeValueAsString(resp);
                }

                Map<String, Object> resp = new HashMap<>();
                resp.put("error", "Sorry, all nearby stations are fully booked for " + effectiveConnectorType + " connectors around that time.");
                resp.put("stations", stationsData);
                resp.put("foundAvailable", false);
                ToolResultHolder.set(new ToolResultHolder.ToolResult(null, null, stationsData));
                return mapper.writeValueAsString(resp);

            } catch (Exception e) {
                log.error("Error in findBestStation tool", e);
                return error("I could not search stations right now. Please try again.");
            }

    }

    private String error(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("success", false, "error", message));
        } catch (Exception ignored) {
            return "{\"success\":false,\"error\":\"AI tool request failed\"}";
        }
    }
    public record CreateBookingInput(
        String stationId,
        String date,
        String startTime,
        String endTime,
        String vehicleNumber
    ) {}

    @Tool(name = "create_booking", description = "Creates a pending booking and Razorpay advance order only after explicit confirmation of a find_best_station result.")
    public String createBooking(
            @ToolParam(description = "Internal id of the station explicitly confirmed by the user.") String stationId,
            @ToolParam(description = "Booking date in yyyy-MM-dd format.") String date,
            @ToolParam(description = "Booking start time in HH:mm format.") String startTime,
            @ToolParam(required = false, description = "Optional booking end time in HH:mm format. Defaults to one hour after startTime.") String endTime,
            @ToolParam(description = "License plate or model of the saved vehicle selected by the user.") String vehicleNumber) {
        return createBooking(new CreateBookingInput(stationId, date, startTime, endTime, vehicleNumber));
    }

    String createBooking(CreateBookingInput input) {

            try {
                log.info("Tool create_booking called with input: {}", input);
                UserContextHolder.UserContext uCtx = UserContextHolder.get();
                String userEmail = uCtx != null ? uCtx.email() : null;

                if (userEmail == null) {
                    return "{\"error\": \"User not authenticated.\" }";
                }
                if (input == null || !hasText(input.stationId()) || !hasText(input.vehicleNumber())
                        || !hasText(input.date()) || !hasText(input.startTime())) {
                    return error("Station, saved vehicle, date, and start time are required before booking.");
                }

                Optional<EvUser> euOpt = evUserRepository.findByEmail(userEmail);
                if (euOpt.isEmpty()) {
                    return "{\"error\": \"EV User profile not found.\" }";
                }
                EvUser evUser = euOpt.get();
                Vehicle vehicle = resolveSelectedVehicle(evUser, input.vehicleNumber());
                String effectiveConnectorType = connectorFor(vehicle);

                Optional<Station> stOpt = stationRepository.findById(Long.parseLong(input.stationId()));
                if (stOpt.isEmpty()) {
                    return "{\"error\": \"Station not found.\" }";
                }
                Station station = stOpt.get();
                if (Boolean.FALSE.equals(station.getOpen()) || station.getStatus() == StationStatus.INACTIVE) {
                    return "{\"error\": \"This station is currently closed and cannot accept new bookings.\" }";
                }

                LocalDate bookingDate = resolveDate(input.date());
                String effectiveStartTime = resolveStartTime(input.startTime(), bookingDate);
                String effectiveEndTime = resolveEndTime(input.endTime(), effectiveStartTime);

                LocalDate today = LocalDate.now(IST);
                LocalTime nowTime = LocalTime.now(IST);
                int currentMinutes = nowTime.getHour() * 60 + nowTime.getMinute();

                if (bookingDate.isBefore(today)) {
                    return "{\"error\": \"Cannot book for a past date.\" }";
                }
                if (bookingDate.equals(today) && timeToMinutes(effectiveStartTime) <= currentMinutes) {
                    return "{\"error\": \"Cannot book a time slot in the past for today.\" }";
                }

                int reqStart = timeToMinutes(effectiveStartTime);
                int reqEnd = timeToMinutes(effectiveEndTime);
                int durationMinutes = reqEnd - reqStart;

                if (reqEnd >= 24 * 60) {
                    return "{\"error\": \"Booking duration cannot cross midnight. Please choose an earlier start time.\" }";
                }
                if (durationMinutes < 60) {
                    return "{\"error\": \"Booking duration cannot be less than 1 hour.\" }";
                }

                if (!isWithinOpeningHours(station, effectiveStartTime, effectiveEndTime)) {
                    return error("The selected time is outside the station opening hours.");
                }

                List<PricingDto> selectedPricing = stationPricing(station);
                List<String> selectedConnectors = stationConnectors(station, selectedPricing);
                if (selectedConnectors.stream().noneMatch(type -> sameConnector(type, effectiveConnectorType))) {
                    return error(station.getName() + " does not support " + effectiveConnectorType + ".");
                }
                int totalPorts = connectorCapacity(
                        station, effectiveConnectorType, selectedConnectors, selectedPricing);
                PricingDto connectorPricing = selectedPricing.stream()
                        .filter(item -> sameConnector(item.getConnectorType(), effectiveConnectorType))
                        .findFirst()
                        .orElse(null);
                double pricePerKWh = connectorPricing != null && connectorPricing.getPriceperKWh() != null
                        ? connectorPricing.getPriceperKWh()
                        : 15.0;
                double durationHours = (double) durationMinutes / 60.0;
                double chargingSpeed = station.getChargingSpeed() != null ? station.getChargingSpeed() : 50.0;
                double estimatedKWh = Math.round((chargingSpeed * durationHours) * 100.0) / 100.0;
                double totalCost = Math.round((estimatedKWh * pricePerKWh) * 100.0) / 100.0;
                double platformFeePercentage = station.getPlatformFee() != null ? station.getPlatformFee() : 20.0;
                double platformFee = Math.round((totalCost * platformFeePercentage / 100.0) * 100.0) / 100.0;
                double grandTotal = Math.round((totalCost + platformFee) * 100.0) / 100.0;

                Instant startInstant = ZonedDateTime.of(bookingDate, LocalTime.parse(effectiveStartTime), IST).toInstant();
                Instant endInstant = ZonedDateTime.of(bookingDate, LocalTime.parse(effectiveEndTime), IST).toInstant();

                List<Booking> activeBookings = activeBookingsForStation(
                        station.getId(), bookingDate, effectiveConnectorType);

                AvailabilityResult avResult = checkAvailability(station.getId(), bookingDate, effectiveStartTime, effectiveEndTime, totalPorts, activeBookings);
                if (!avResult.available()) {
                    return "{\"error\": \"Conflict detected: This slot is no longer available. Please try another time.\" }";
                }

                Booking booking = Booking.builder()
                        .user(evUser)
                        .station(station)
                        .vehicle(vehicle)
                        .startTime(startInstant)
                        .endTime(endInstant)
                        .connectorType(connectorType(effectiveConnectorType))
                        .vehicleNumber(vehicle != null ? vehicle.getLicensePlate() : null)
                        .durationMinutes(durationMinutes)
                        .estimatedKWh(estimatedKWh)
                        .totalCost(totalCost)
                        .platformFee(platformFee)
                        .grandTotal(grandTotal)
                        .status(BookingStatus.PENDING)
                        .otp(String.valueOf(100000 + new Random().nextInt(900000)))
                        .otpExpiresAt(startInstant.plus(Duration.ofMinutes(30)))
                        .createdAt(Instant.now())
                        .build();

                bookingRepository.save(booking);

                BigDecimal advanceAmount = BigDecimal.valueOf(grandTotal)
                        .multiply(BigDecimal.valueOf(0.20))
                        .setScale(2, RoundingMode.HALF_UP);
                PaymentResponseDto order;
                try {
                    order = paymentService.createOrder(PaymentRequestDto.builder()
                            .bookingId(booking.getId())
                            .amount(advanceAmount)
                            .currency(connectorPricing != null && connectorPricing.getCurrency() != null
                                    ? connectorPricing.getCurrency() : CurrencyCode.INR)
                            .build());
                } catch (Exception paymentError) {
                    bookingRepository.delete(booking);
                    throw paymentError;
                }

                RazorpayCheckoutDto checkout = RazorpayCheckoutDto.builder()
                        .keyId(order.getKeyId())
                        .orderId(order.getOrderId())
                        .amount(order.getAmount())
                        .currency(order.getCurrency())
                        .bookingId(booking.getId())
                        .description("20% advance for " + station.getName())
                        .build();

                Map<String, Object> resp = new HashMap<>();
                resp.put("success", true);
                resp.put("bookingId", booking.getId().toString());
                resp.put("checkout", checkout);
                resp.put("message", "Booking is reserved. Open Razorpay Checkout now to pay the 20% advance.");

                ToolResultHolder.set(new ToolResultHolder.ToolResult(
                        booking.getId().toString(), true, null, checkout));
                return objectMapper.writeValueAsString(resp);

            } catch (Exception e) {
                log.error("Error in createBooking tool", e);
                return error("I could not create the booking or start payment. Please try again.");
            }
    }

    private ConnectorType connectorType(String connectorType) {
        if (connectorType == null || connectorType.isBlank()) return ConnectorType.CCS2;
        try {
            return ConnectorType.valueOf(connectorType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return ConnectorType.CCS2;
        }
    }
}
