package com.voltx.evgenee.service.impl;

import com.voltx.evgenee.dto.requests.SosRequestDto;
import com.voltx.evgenee.dto.responses.SosResponseDto;
import com.voltx.evgenee.entity.RoadsideRequest;
import com.voltx.evgenee.exceptions.BadRequestException;
import com.voltx.evgenee.exceptions.ResourceNotFoundException;
import com.voltx.evgenee.repository.RoadsideRequestRepository;
import com.voltx.evgenee.repository.EvUserRepository;
import com.voltx.evgenee.notification.EmailNotificationPublisher;
import com.voltx.evgenee.service.RoadsideService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RoadSideImpl implements RoadsideService {

    private final RoadsideRequestRepository roadsideRequestRepository;
    private final EvUserRepository evUserRepository;
    private final EmailNotificationPublisher emailNotifications;

    private static final Map<String, String> ISSUE_LABELS = Map.of(
            "flat_tire", "Flat tire",
            "battery_dead", "Battery depleted",
            "charging_issue", "Charging issue",
            "accident", "Accident assistance",
            "tow", "Tow request",
            "other", "Other issue");

    @Override
    public List<String> getIssueTypes() {
        return ISSUE_LABELS.keySet().stream().sorted().toList();
    }

    @Override
    public SosResponseDto.MechanicDto getNearestMechanic(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            throw new BadRequestException("Latitude and longitude are required");
        }
        return defaultMechanic();
    }

    @Override
    @Transactional
    public SosResponseDto createSOSRequest(SosRequestDto requestDto) {
        SosResponseDto.MechanicDto mechanic = defaultMechanic();
        RoadsideRequest request = RoadsideRequest.builder()
                .status(Boolean.TRUE.equals(requestDto.getRequestTow()) ? "tow_dispatched" : "mechanic_assigned")
                .issueType(requestDto.getIssueType())
                .issueLabel(ISSUE_LABELS.getOrDefault(requestDto.getIssueType(), requestDto.getIssueType()))
                .towRequested(Boolean.TRUE.equals(requestDto.getRequestTow()))
                .address(requestDto.getAddress())
                .description(requestDto.getDescription())
                .latitude(requestDto.getLatitude())
                .longitude(requestDto.getLongitude())
                .userEmail(currentEmail())
                .mechanicName(mechanic.getName())
                .mechanicPhone(mechanic.getPhone())
                .mechanicGarage(mechanic.getGarage())
                .mechanicEstimatedArrival(mechanic.getEstimatedArrival())
                .mechanicDistance(mechanic.getDistance())
                .mechanicRating(mechanic.getRating())
                .mechanicSpeciality(mechanic.getSpeciality())
                .build();
        RoadsideRequest saved = roadsideRequestRepository.save(request);
        String userName = evUserRepository.findByEmail(saved.getUserEmail())
                .map(user -> user.getFullName())
                .orElse("Driver");
        emailNotifications.roadsideDispatched(saved, userName);
        return toResponse(saved);
    }

    @Override
    public List<SosResponseDto> getMyRequests() {
        return roadsideRequestRepository.findByUserEmailOrderByCreatedAtDesc(currentEmail()).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public SosResponseDto getRequestDetails(Long requestId) {
        return toResponse(getRequest(requestId));
    }

    @Override
    @Transactional
    public SosResponseDto cancelRequest(Long requestId) {
        RoadsideRequest request = getRequest(requestId);
        request.setStatus("cancelled");
        request.setCancelledAt(Instant.now());
        return toResponse(roadsideRequestRepository.save(request));
    }

    private RoadsideRequest getRequest(Long requestId) {
        return roadsideRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("SOS request not found: " + requestId));
    }

    private SosResponseDto toResponse(RoadsideRequest request) {
        return SosResponseDto.builder()
                .requestId(String.valueOf(request.getId()))
                .status(request.getStatus())
                .issueType(request.getIssueType())
                .issueLabel(request.getIssueLabel())
                .towRequested(request.getTowRequested())
                .address(request.getAddress())
                .description(request.getDescription())
                .mechanic(SosResponseDto.MechanicDto.builder()
                        .name(request.getMechanicName())
                        .phone(request.getMechanicPhone())
                        .garage(request.getMechanicGarage())
                        .estimatedArrival(request.getMechanicEstimatedArrival())
                        .distance(request.getMechanicDistance())
                        .rating(request.getMechanicRating())
                        .speciality(request.getMechanicSpeciality())
                        .build())
                .createdAt(request.getCreatedAt() == null ? null : request.getCreatedAt().toString())
                .resolvedAt(request.getResolvedAt() == null ? null : request.getResolvedAt().toString())
                .cancelledAt(request.getCancelledAt() == null ? null : request.getCancelledAt().toString())
                .build();
    }

    private SosResponseDto.MechanicDto defaultMechanic() {
        return SosResponseDto.MechanicDto.builder()
                .name("EvGenee Rapid Assist")
                .phone("+91-90000-20300")
                .garage("VoltX Mobile Service Hub")
                .estimatedArrival("20-30 mins")
                .distance("3.2 km")
                .rating(4.8)
                .speciality("EV roadside support")
                .build();
    }

    private String currentEmail() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) throw new BadRequestException("Authenticated user required");
        return auth.getName();
    }
}
