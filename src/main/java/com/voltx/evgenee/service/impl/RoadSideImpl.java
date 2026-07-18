package com.voltx.evgenee.service.impl;

import com.voltx.evgenee.configuration.RedisConfig;
import com.voltx.evgenee.dto.requests.SosRequestDto;
import com.voltx.evgenee.dto.responses.SosResponseDto;
import com.voltx.evgenee.entity.RoadsideRequest;
import com.voltx.evgenee.entity.Station;
import com.voltx.evgenee.entity.StationMechanic;
import com.voltx.evgenee.enums.ApprovalRequiredBy;
import com.voltx.evgenee.enums.RoadsideIssueType;
import com.voltx.evgenee.enums.RoadsideStatus;
import com.voltx.evgenee.enums.SupportProvider;
import com.voltx.evgenee.exceptions.BadRequestException;
import com.voltx.evgenee.exceptions.ResourceNotFoundException;
import com.voltx.evgenee.repository.RoadsideRequestRepository;
import com.voltx.evgenee.repository.EvUserRepository;
import com.voltx.evgenee.repository.StationMechanicRepository;
import com.voltx.evgenee.repository.StationOwnerRepository;
import com.voltx.evgenee.repository.StationRepository;
import com.voltx.evgenee.notification.EmailNotificationPublisher;
import com.voltx.evgenee.service.RoadsideService;
import com.voltx.evgenee.socket.RealtimeNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoadSideImpl implements RoadsideService {

    private final RoadsideRequestRepository roadsideRequestRepository;
    private final StationMechanicRepository stationMechanicRepository;
    private final StationOwnerRepository stationOwnerRepository;
    private final StationRepository stationRepository;
    private final EvUserRepository evUserRepository;
    private final EmailNotificationPublisher emailNotifications;
    private final RealtimeNotificationService realtimeNotificationService;

    @Override
    @Cacheable(cacheNames = RedisConfig.ROADSIDE_STATIC, key = "'issue-types'")
    public List<String> getIssueTypes() {
        return Arrays.stream(RoadsideIssueType.values())
                .map(RoadsideIssueType::name)
                .sorted()
                .toList();
    }

    @Override
    @Cacheable(cacheNames = RedisConfig.ROADSIDE_STATIC, key = "T(java.lang.String).format('mechanic:%.5f:%.5f', #latitude, #longitude)", condition = "#latitude != null && #longitude != null")
    public SosResponseDto.MechanicDto getNearestMechanic(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            throw new BadRequestException("Latitude and longitude are required");
        }
        return nearestMechanic(latitude, longitude);
    }

    @Override
    @Transactional
    public SosResponseDto createSOSRequest(SosRequestDto requestDto) {
        requireLocation(requestDto.getLatitude(), requestDto.getLongitude());
        SupportProvider supportProvider = requestDto.getSupportProvider() == null
                ? SupportProvider.EVGENEE
                : requestDto.getSupportProvider();
        RoadsideIssueType issueType = requestDto.getIssueType() == null
                ? RoadsideIssueType.OTHER
                : requestDto.getIssueType();
        AssignedMechanic assigned = supportProvider == SupportProvider.STATION
                ? assignedStationMechanic(requestDto.getStationId(), requestDto.getLatitude(), requestDto.getLongitude())
                : null;
        SosResponseDto.MechanicDto mechanic = assigned == null ? null : assigned.mechanic();
        RoadsideRequest request = RoadsideRequest.builder()
                .status(supportProvider == SupportProvider.STATION ? RoadsideStatus.PENDING_STATION_APPROVAL : RoadsideStatus.PENDING_ADMIN_APPROVAL)
                .supportProvider(supportProvider)
                .approvalRequiredBy(supportProvider == SupportProvider.STATION ? ApprovalRequiredBy.STATION : ApprovalRequiredBy.ADMIN)
                .issueType(issueType)
                .issueLabel(issueLabel(issueType))
                .towRequested(Boolean.TRUE.equals(requestDto.getRequestTow()))
                .address(requestDto.getAddress())
                .description(requestDto.getDescription())
                .latitude(requestDto.getLatitude())
                .longitude(requestDto.getLongitude())
                .userEmail(currentEmail())
                .mechanicName(mechanic == null ? null : mechanic.getName())
                .mechanicPhone(mechanic == null ? null : mechanic.getPhone())
                .mechanicGarage(mechanic == null ? null : mechanic.getGarage())
                .mechanicEstimatedArrival(mechanic == null ? null : mechanic.getEstimatedArrival())
                .mechanicDistance(mechanic == null ? null : mechanic.getDistance())
                .mechanicRating(mechanic == null ? null : mechanic.getRating())
                .mechanicSpeciality(mechanic == null ? null : mechanic.getSpeciality())
                .stationId(assigned == null ? null : assigned.stationId())
                .stationName(assigned == null ? null : assigned.stationName())
                .stationMechanicId(assigned == null ? null : assigned.mechanicId())
                .build();
        RoadsideRequest saved = roadsideRequestRepository.save(request);
        notifyAdminRoadsideAssistance(saved);
        if (supportProvider == SupportProvider.STATION && saved.getStationId() != null) {
            notifyStationRoadsideAssistance(saved);
        }
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
        request.setStatus(RoadsideStatus.CANCELLED);
        request.setCancelledAt(Instant.now());
        return toResponse(roadsideRequestRepository.save(request));
    }


    @Override
    public List<SosResponseDto> getAllRequests() {
        return roadsideRequestRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public List<SosResponseDto> getStationRequests(String ownerEmail) {
        List<Long> stationIds = stationOwnerRepository.findByEmail(ownerEmail)
                .map(owner -> stationRepository.findByOwnerId(owner.getId()).stream()
                        .map(Station::getId)
                        .toList())
                .orElse(List.of());
        if (stationIds.isEmpty()) return List.of();
        return roadsideRequestRepository.findByStationIdInOrderByCreatedAtDesc(stationIds).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public SosResponseDto updateRequestStatus(Long requestId, String status) {
        RoadsideRequest request = getRequest(requestId);
        RoadsideStatus normalizedStatus = roadsideStatus(status, RoadsideStatus.MECHANIC_ASSIGNED);
        boolean approvingDispatch = request.getStatus() != null
                && (request.getStatus() == RoadsideStatus.PENDING_ADMIN_APPROVAL
                || request.getStatus() == RoadsideStatus.PENDING_STATION_APPROVAL)
                && (normalizedStatus == RoadsideStatus.MECHANIC_ASSIGNED || normalizedStatus == RoadsideStatus.TOW_DISPATCHED);
        if (approvingDispatch) {
            approveDispatch(request, normalizedStatus);
        } else {
            request.setStatus(normalizedStatus);
        }
        if (request.getStatus() == RoadsideStatus.RESOLVED) {
            request.setResolvedAt(Instant.now());
        }
        if (request.getStatus() == RoadsideStatus.CANCELLED) {
            request.setCancelledAt(Instant.now());
        }
        RoadsideRequest saved = roadsideRequestRepository.save(request);
        notifyRoadsideUpdate(saved);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public SosResponseDto updateStationRequestStatus(String ownerEmail, Long requestId, String status) {
        boolean owned = getStationRequests(ownerEmail).stream()
                .anyMatch(request -> String.valueOf(requestId).equals(request.getRequestId()));
        if (!owned) {
            throw new BadRequestException("Roadside request is not assigned to your station");
        }
        return updateRequestStatus(requestId, status);
    }
    private RoadsideRequest getRequest(Long requestId) {
        return roadsideRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("SOS request not found: " + requestId));
    }

    private SosResponseDto toResponse(RoadsideRequest request) {
        return SosResponseDto.builder()
                .requestId(String.valueOf(request.getId()))
                .status(request.getStatus())
                .supportProvider(request.getSupportProvider())
                .approvalRequiredBy(request.getApprovalRequiredBy())
                .issueType(request.getIssueType())
                .issueLabel(request.getIssueLabel())
                .towRequested(request.getTowRequested())
                .address(request.getAddress())
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .description(request.getDescription())
                .mechanic(request.getMechanicName() == null ? null : SosResponseDto.MechanicDto.builder()
                        .name(request.getMechanicName())
                        .phone(request.getMechanicPhone())
                        .garage(request.getMechanicGarage())
                        .estimatedArrival(request.getMechanicEstimatedArrival())
                        .distance(request.getMechanicDistance())
                        .rating(request.getMechanicRating())
                        .speciality(request.getMechanicSpeciality())
                        .build())
                .stationId(request.getStationId() == null ? null : String.valueOf(request.getStationId()))
                .stationName(request.getStationName())
                .createdAt(request.getCreatedAt() == null ? null : request.getCreatedAt().toString())
                .resolvedAt(request.getResolvedAt() == null ? null : request.getResolvedAt().toString())
                .cancelledAt(request.getCancelledAt() == null ? null : request.getCancelledAt().toString())
                .build();
    }


    private AssignedMechanic nearestAssignedMechanic(Double latitude, Double longitude) {
        requireLocation(latitude, longitude);
        List<StationMechanic> mechanics = stationMechanicRepository.findAvailableActiveMechanics();
        StationMechanic best = mechanics.stream()
                .filter(mechanic -> mechanic.getStation() != null
                        && mechanic.getStation().getLatitude() != null
                        && mechanic.getStation().getLongitude() != null)
                .min((a, b) -> Double.compare(
                        haversine(latitude, longitude, a.getStation().getLatitude(), a.getStation().getLongitude()),
                        haversine(latitude, longitude, b.getStation().getLatitude(), b.getStation().getLongitude())))
                .orElse(null);
        if (best == null) {
            throw new ResourceNotFoundException("No active mechanic available near this location");
        }
        Station station = best.getStation();
        double distanceKm = Math.round(haversine(latitude, longitude, station.getLatitude(), station.getLongitude()) * 10.0) / 10.0;
        SosResponseDto.MechanicDto mechanic = SosResponseDto.MechanicDto.builder()
                .name(best.getName())
                .phone(best.getPhone())
                .garage(best.getGarage())
                .estimatedArrival(distanceKm <= 5 ? "15-25 mins" : "25-40 mins")
                .distance(distanceKm + " km")
                .rating(best.getRating())
                .speciality(best.getSpeciality())
                .build();
        return new AssignedMechanic(mechanic, station.getId(), station.getName(), best.getId());
    }

    private AssignedMechanic assignedStationMechanic(Long requestedStationId, Double latitude, Double longitude) {
        requireLocation(latitude, longitude);
        if (requestedStationId == null) {
            return nearestAssignedMechanic(latitude, longitude);
        }
        StationMechanic best = stationMechanicRepository.findAvailableActiveMechanics().stream()
                .filter(mechanic -> mechanic.getStation() != null
                        && requestedStationId.equals(mechanic.getStation().getId())
                        && mechanic.getStation().getLatitude() != null
                        && mechanic.getStation().getLongitude() != null)
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("No active mechanic available for the selected station"));
        Station station = best.getStation();
        double distanceKm = Math.round(haversine(latitude, longitude, station.getLatitude(), station.getLongitude()) * 10.0) / 10.0;
        SosResponseDto.MechanicDto mechanic = SosResponseDto.MechanicDto.builder()
                .name(best.getName())
                .phone(best.getPhone())
                .garage(best.getGarage())
                .estimatedArrival(distanceKm <= 5 ? "15-25 mins" : "25-40 mins")
                .distance(distanceKm + " km")
                .rating(best.getRating())
                .speciality(best.getSpeciality())
                .build();
        return new AssignedMechanic(mechanic, station.getId(), station.getName(), best.getId());
    }

    private void approveDispatch(RoadsideRequest request, RoadsideStatus requestedStatus) {
        if (request.getStationId() == null || request.getStationMechanicId() == null) {
            AssignedMechanic assigned = nearestAssignedMechanic(request.getLatitude(), request.getLongitude());
            applyAssignment(request, assigned);
        }
        request.setStatus(Boolean.TRUE.equals(request.getTowRequested()) ? RoadsideStatus.TOW_DISPATCHED : requestedStatus);
        request.setApprovalRequiredBy(null);
        String userName = evUserRepository.findByEmail(request.getUserEmail())
                .map(user -> user.getFullName())
                .orElse("Driver");
        emailNotifications.roadsideDispatched(request, userName);
    }

    private void applyAssignment(RoadsideRequest request, AssignedMechanic assigned) {
        SosResponseDto.MechanicDto mechanic = assigned.mechanic();
        request.setMechanicName(mechanic.getName());
        request.setMechanicPhone(mechanic.getPhone());
        request.setMechanicGarage(mechanic.getGarage());
        request.setMechanicEstimatedArrival(mechanic.getEstimatedArrival());
        request.setMechanicDistance(mechanic.getDistance());
        request.setMechanicRating(mechanic.getRating());
        request.setMechanicSpeciality(mechanic.getSpeciality());
        request.setStationId(assigned.stationId());
        request.setStationName(assigned.stationName());
        request.setStationMechanicId(assigned.mechanicId());
    }

    private SosResponseDto.MechanicDto nearestMechanic(Double latitude, Double longitude) {
        return nearestAssignedMechanic(latitude, longitude).mechanic();
    }

    private void notifyAdminRoadsideAssistance(RoadsideRequest request) {
        try {
            realtimeNotificationService.notifyRoadsideAssistanceCreated(toResponse(request));
        } catch (Exception e) {
            log.warn("Unable to emit roadside assistance admin event: {}", e.getMessage());
        }
    }

    private void notifyStationRoadsideAssistance(RoadsideRequest request) {
        try {
            realtimeNotificationService.notifyStationRoadsideAssistanceCreated(String.valueOf(request.getStationId()), toResponse(request));
        } catch (Exception e) {
            log.warn("Unable to emit roadside assistance station event: {}", e.getMessage());
        }
    }

    private void notifyRoadsideUpdate(RoadsideRequest request) {
        try {
            realtimeNotificationService.notifyRoadsideAssistanceUpdated(
                    request.getStationId() == null ? null : String.valueOf(request.getStationId()),
                    toResponse(request));
        } catch (Exception e) {
            log.warn("Unable to emit roadside assistance update event: {}", e.getMessage());
        }
    }

    private void requireLocation(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            throw new BadRequestException("Latitude and longitude are required");
        }
    }

    private String issueLabel(RoadsideIssueType issueType) {
        return switch (issueType) {
            case FLAT_TIRE -> "Flat tire";
            case BATTERY_DEAD -> "Battery depleted";
            case CHARGING_ISSUE -> "Charging issue";
            case ACCIDENT -> "Accident assistance";
            case TOW -> "Tow request";
            default -> "Other issue";
        };
    }

    private RoadsideStatus roadsideStatus(String status, RoadsideStatus fallback) {
        if (status == null || status.isBlank()) return fallback;
        try {
            return RoadsideStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371 * (2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
    }

    private record AssignedMechanic(
            SosResponseDto.MechanicDto mechanic,
            Long stationId,
            String stationName,
            Long mechanicId) {}

    private String currentEmail() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) throw new BadRequestException("Authenticated user required");
        return auth.getName();
    }
}
