package com.voltx.evgenee.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltx.evgenee.dto.common.*;
import com.voltx.evgenee.dto.requests.ReviewRequestDto;
import com.voltx.evgenee.dto.requests.StationRequestDto;
import com.voltx.evgenee.dto.responses.ReviewResponseDto;
import com.voltx.evgenee.dto.responses.StationResponseDto;
import com.voltx.evgenee.entity.EvUser;
import com.voltx.evgenee.entity.Review;
import com.voltx.evgenee.entity.Station;
import com.voltx.evgenee.entity.StationOwner;
import com.voltx.evgenee.entity.User;
import com.voltx.evgenee.exceptions.BadRequestException;
import com.voltx.evgenee.exceptions.ResourceNotFoundException;
import com.voltx.evgenee.repository.EvUserRepository;
import com.voltx.evgenee.repository.ReviewRepository;
import com.voltx.evgenee.repository.StationOwnerRepository;
import com.voltx.evgenee.repository.StationRepository;
import com.voltx.evgenee.repository.UserRepository;
import com.voltx.evgenee.service.StationService;
import com.voltx.evgenee.socket.RealtimeNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class StationServiceImpl implements StationService {

    private final StationRepository stationRepository;
    private final StationOwnerRepository stationOwnerRepository;
    private final EvUserRepository evUserRepository;
    private final ReviewRepository reviewRepository;
    private final UserRepository userRepository;
    private final RealtimeNotificationService realtimeNotificationService;
    private final ObjectMapper objectMapper;

    @Value("${platform.fee.percentage:20.0}")
    private Double defaultPlatformFee;

    @Override
    @Transactional(readOnly = true)
    public List<StationResponseDto> getNearbyStations(Double latitude, Double longitude, Double radius) {
        List<StationResponseDto> list = stationRepository.findAll().stream()
                .map(station -> toResponse(station, latitude, longitude))
                .filter(station -> radius == null || station.getDistanceKm() == null || station.getDistanceKm() <= radius)
                .sorted(Comparator.comparing(s -> s.getDistanceKm() == null ? Double.MAX_VALUE : s.getDistanceKm()))
                .toList();

        for(StationResponseDto dto : list) System.out.println(dto);
        return list;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StationResponseDto> getAllStations() {
        return stationRepository.findAll().stream().map(station -> toResponse(station, null, null)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StationResponseDto> getStationsByOwner(Long ownerId) {
        Long stationOwnerId = stationOwnerRepository.findByAuthUserId(ownerId)
                .map(StationOwner::getId)
                .orElse(ownerId);
        return stationRepository.findByOwnerId(stationOwnerId).stream().map(station -> toResponse(station, null, null)).toList();
    }

    @Override
    @Transactional
    public void updateStationStatus(Long stationId, String status) {
        Station station = getStation(stationId);
        station.setStatus(normalizeStatus(status));
        station.setOpen("active".equals(station.getStatus()));
        stationRepository.save(station);
        notifyStationStatus(station);
    }

    @Override
    @Transactional
    public void suspendStation(Long stationId) {
        updateStationStatus(stationId, "inactive");
    }

    @Override
    public void deleteStation(Long stationId) {
        stationRepository.delete(getStation(stationId));
    }

    @Override
    @Transactional
    public StationResponseDto addStation(StationRequestDto request) {
        String email = currentEmail();
        StationOwner owner = stationOwnerRepository.findByEmail(email)
                .orElseGet(() -> {
                    User authUser = userRepository.findByEmail(email)
                            .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
                    return stationOwnerRepository.save(StationOwner.builder()
                            .name(request.getOwnerofStation() != null ? request.getOwnerofStation() : request.getOperator())
                            .contact(request.getContactInfo() == null ? "" : request.getContactInfo().getPhoneNumber())
                            .authUser(authUser)
                            .build());
                });
        Station station = new Station();
        station.setOwner(owner);
        applyRequest(station, request);
        Station saved = stationRepository.save(station);
        notifyStationUpdated(saved);
        return toResponse(saved, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StationResponseDto> getMyStations(String ownerEmail) {
        StationOwner owner = stationOwnerRepository.findByEmail(ownerEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Station owner profile not found"));
        return stationRepository.findByOwnerId(owner.getId()).stream().map(station -> toResponse(station, null, null)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public StationResponseDto getStationById(Long stationId) {
        return toResponse(getStation(stationId), null, null);
    }

    @Override
    @Transactional
    public StationResponseDto updateStation(Long stationId, StationRequestDto request) {
        Station station = getStation(stationId);
        applyRequest(station, request);
        Station saved = stationRepository.save(station);
        notifyStationUpdated(saved);
        return toResponse(saved, null, null);
    }

    @Override
    @Transactional
    public ReviewResponseDto addReview(Long stationId, ReviewRequestDto request) {
        Station station = getStation(stationId);
        String email = currentEmail();
        EvUser user = evUserRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("EV user profile not found"));
        Review review = Review.builder()
                .station(station)
                .user(user)
                .rating(request.getRating())
                .comment(request.getComment())
                .createdAt(Instant.now())
                .build();
        return toReviewResponse(reviewRepository.save(review));
    }

    @Override
    @Transactional
    public void toggleStationStatus(Long stationId) {
        Station station = getStation(stationId);
        boolean open = station.getOpen() == null || !station.getOpen();
        station.setOpen(open);
        station.setStatus(open ? "active" : "inactive");
        stationRepository.save(station);
        notifyStationStatus(station);
    }

    private void applyRequest(Station station, StationRequestDto request) {
        station.setName(value(request.getName(), station.getName()));
        station.setOwnerName(value(request.getOwnerofStation(), station.getOwnerName()));
        station.setChargersCount(value(request.getTotalPorts(), station.getChargersCount()));
        station.setAvailablePorts(value(request.getAvailablePorts(), station.getAvailablePorts()));
        station.setChargingSpeed(value(request.getChargingSpeed(), station.getChargingSpeed()));
        station.setPlatformFee(value(request.getPlatformFee(), station.getPlatformFee() != null ? station.getPlatformFee() : defaultPlatformFee));
        station.setOpen(value(request.getIsOpen(), station.getOpen() != null ? station.getOpen() : true));
        station.setOpeningHours(value(request.getOpeningHours(), station.getOpeningHours() != null ? station.getOpeningHours() : "08:00 - 22:00"));
        station.setStatus(normalizeStatus(value(request.getStatus(), station.getStatus() != null ? station.getStatus() : "active")));
        station.setOperator(value(request.getOperator(), station.getOperator()));

        if (request.getLocation() != null && request.getLocation().getCoordinates() != null && request.getLocation().getCoordinates().size() >= 2) {
            station.setLongitude(request.getLocation().getCoordinates().get(0));
            station.setLatitude(request.getLocation().getCoordinates().get(1));
        }
        if (request.getAddress() != null) {
            station.setAddressJson(write(request.getAddress()));
            station.setAddress(joinAddress(request.getAddress()));
        }
        if (request.getContactInfo() != null) {
            station.setContactPhone(request.getContactInfo().getPhoneNumber());
            station.setContactEmail(request.getContactInfo().getEmail());
        }
        station.setAmenitiesJson(write(request.getAmenities()));
        station.setConnectorsJson(write(request.getTypeOfConnectors()));
        station.setPricingJson(write(request.getPricing()));
        station.setImagesJson(write(request.getImages()));
        station.setMechanicJson(write(request.getMechanic()));
        station.setPeakPricingJson(write(request.getPeakPricing()));
    }

    private StationResponseDto toResponse(Station station, Double userLat, Double userLng) {
        Integer totalPorts = station.getChargersCount() != null ? station.getChargersCount() : 4;
        List<PricingDto> pricing = read(station.getPricingJson(), new TypeReference<List<PricingDto>>() {}, defaultPricing(totalPorts));
        List<String> connectors = read(station.getConnectorsJson(), new TypeReference<List<String>>() {},
                pricing.stream().map(PricingDto::getConnectorType).toList());
        AddressDto address = read(station.getAddressJson(), new TypeReference<AddressDto>() {}, fallbackAddress(station.getAddress()));
        Double distance = userLat != null && userLng != null && station.getLatitude() != null && station.getLongitude() != null
                ? round(haversine(userLat, userLng, station.getLatitude(), station.getLongitude()))
                : null;

        Object owner = station.getOwner() == null
                ? station.getOwnerName()
                : Map.of("_id", station.getOwner().getAuthUser() == null
                                ? String.valueOf(station.getOwner().getId())
                                : String.valueOf(station.getOwner().getAuthUser().getId()),
                        "name", station.getOwner().getName() == null ? "" : station.getOwner().getName(),
                        "email", station.getOwner().getAuthUser() == null ? "" : station.getOwner().getAuthUser().getEmail());

        return StationResponseDto.builder()
                .id(station.getId())
                ._id(station.getId() == null ? null : String.valueOf(station.getId()))
                .name(station.getName())
                .ownerofStation(owner)
                .location(LocationDto.builder().type("Point").coordinates(List.of(
                        station.getLongitude() != null ? station.getLongitude() : 77.4126,
                        station.getLatitude() != null ? station.getLatitude() : 23.2599)).build())
                .address(address)
                .amenities(read(station.getAmenitiesJson(), new TypeReference<List<String>>() {}, List.of()))
                .totalPorts(totalPorts)
                .availablePorts(station.getAvailablePorts() != null ? station.getAvailablePorts() : totalPorts)
                .chargingSpeed(station.getChargingSpeed() != null ? station.getChargingSpeed() : 50)
                .typeOfConnectors(connectors)
                .pricing(pricing)
                .platformFee(station.getPlatformFee() != null ? station.getPlatformFee() : defaultPlatformFee)
                .isOpen(station.getOpen() == null || station.getOpen())
                .openingHours(station.getOpeningHours() != null ? station.getOpeningHours() : "08:00 - 22:00")
                .contactInfo(ContactInfoDto.builder().phoneNumber(station.getContactPhone()).email(station.getContactEmail()).build())
                .status(station.getStatus() != null ? station.getStatus() : "active")
                .operator(station.getOperator())
                .Images(read(station.getImagesJson(), new TypeReference<List<String>>() {}, List.of()))
                .mechanic(read(station.getMechanicJson(), new TypeReference<MechanicDto>() {}, null))
                .reviews(station.getReviews() == null ? List.of() : station.getReviews().stream()
                        .map(r -> ReviewDto.builder()
                                .userId(r.getUser() == null ? null : String.valueOf(r.getUser().getId()))
                                .comment(r.getComment())
                                .rating(r.getRating() == null ? null : r.getRating().doubleValue())
                                .build())
                        .toList())
                .distance(distance)
                .distanceKm(distance)
                .peakPricing(read(station.getPeakPricingJson(), new TypeReference<List<PeakPricingDto>>() {}, List.of()))
                .build();
    }

    private ReviewResponseDto toReviewResponse(Review review) {
        return ReviewResponseDto.builder()
                .id(review.getId())
                .stationId(review.getStation().getId())
                .userId(review.getUser().getId())
                .userName(review.getUser().getFullName())
                .rating(review.getRating())
                .comment(review.getComment())
                .createdAt(review.getCreatedAt())
                .build();
    }

    private Station getStation(Long stationId) {
        return stationRepository.findById(stationId)
                .orElseThrow(() -> new ResourceNotFoundException("Station not found: " + stationId));
    }

    private String currentEmail() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) throw new BadRequestException("Authenticated user required");
        return auth.getName();
    }

    private <T> T value(T incoming, T fallback) {
        return incoming != null ? incoming : fallback;
    }

    private String normalizeStatus(String status) {
        return status == null || status.equalsIgnoreCase("active") ? "active" : "inactive";
    }

    private String write(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BadRequestException("Invalid station data");
        }
    }

    private <T> T read(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) return fallback;
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private List<PricingDto> defaultPricing(Integer totalPorts) {
        return List.of(
                PricingDto.builder().connectorType("CCS2").priceperKWh(15.0).portCount(Math.max(1, totalPorts / 2)).currency("INR").build(),
                PricingDto.builder().connectorType("Type2").priceperKWh(12.0).portCount(Math.max(1, totalPorts / 2)).currency("INR").build());
    }

    private AddressDto fallbackAddress(String address) {
        return AddressDto.builder().city(address).state("").country("India").postalCode("").street(address).build();
    }

    private String joinAddress(AddressDto address) {
        return String.join(", ", List.of(
                address.getCity() == null ? "" : address.getCity(),
                address.getState() == null ? "" : address.getState(),
                address.getCountry() == null ? "" : address.getCountry())).replaceAll("(^,\\s*)|(,\\s*$)", "");
    }

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371 * (2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private void notifyStationUpdated(Station station) {
        try {
            realtimeNotificationService.notifyStationUpdated(String.valueOf(station.getId()), toResponse(station, null, null));
        } catch (Exception e) {
            log.warn("Unable to emit station update event: {}", e.getMessage());
        }
    }

    private void notifyStationStatus(Station station) {
        try {
            realtimeNotificationService.notifyStationStatusChanged(String.valueOf(station.getId()), station.getName(), station.getOpen());
        } catch (Exception e) {
            log.warn("Unable to emit station status event: {}", e.getMessage());
        }
    }
}
