package com.voltx.evgenee.service.impl;

import com.voltx.evgenee.dto.common.*;
import com.voltx.evgenee.dto.requests.ReviewRequestDto;
import com.voltx.evgenee.dto.requests.StationRequestDto;
import com.voltx.evgenee.dto.responses.ReviewResponseDto;
import com.voltx.evgenee.dto.responses.StationResponseDto;
import com.voltx.evgenee.entity.EvUser;
import com.voltx.evgenee.entity.Review;
import com.voltx.evgenee.entity.Station;
import com.voltx.evgenee.entity.StationAddress;
import com.voltx.evgenee.entity.StationAmenity;
import com.voltx.evgenee.entity.StationConnector;
import com.voltx.evgenee.entity.StationImage;
import com.voltx.evgenee.entity.StationMechanic;
import com.voltx.evgenee.entity.StationOwner;
import com.voltx.evgenee.entity.StationPeakPricing;
import com.voltx.evgenee.entity.StationPricing;
import com.voltx.evgenee.enums.ConnectorType;
import com.voltx.evgenee.enums.CurrencyCode;
import com.voltx.evgenee.enums.StationApprovalStatus;
import com.voltx.evgenee.enums.StationStatus;
import com.voltx.evgenee.entity.User;
import com.voltx.evgenee.exceptions.BadRequestException;
import com.voltx.evgenee.exceptions.ResourceNotFoundException;
import com.voltx.evgenee.repository.EvUserRepository;
import com.voltx.evgenee.repository.ReviewRepository;
import com.voltx.evgenee.repository.StationOwnerRepository;
import com.voltx.evgenee.repository.StationMechanicRepository;
import com.voltx.evgenee.repository.StationRepository;
import com.voltx.evgenee.configuration.RedisConfig;
import com.voltx.evgenee.repository.UserRepository;
import com.voltx.evgenee.service.StationService;
import com.voltx.evgenee.socket.RealtimeNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class StationServiceImpl implements StationService {

    private final StationRepository stationRepository;
    private final StationOwnerRepository stationOwnerRepository;
    private final StationMechanicRepository stationMechanicRepository;
    private final EvUserRepository evUserRepository;
    private final ReviewRepository reviewRepository;
    private final UserRepository userRepository;
    private final RealtimeNotificationService realtimeNotificationService;

    @Value("${platform.fee.percentage:20.0}")
    private Double defaultPlatformFee;

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = RedisConfig.STATIONS_NEARBY, key = "T(java.lang.String).format('lat:%.5f:lng:%.5f:r:%s', #latitude, #longitude, #radius == null ? 'all' : #radius)", condition = "#latitude != null && #longitude != null")
    public List<StationResponseDto> getNearbyStations(Double latitude, Double longitude, Double radius) {
        List<StationResponseDto> list = stationRepository
                .findByApprovalStatusAndLatitudeIsNotNullAndLongitudeIsNotNullOrderByIdDesc(StationApprovalStatus.APPROVED)
                .stream()
                .map(station -> toResponse(station, latitude, longitude))
                .filter(station -> radius == null || station.getDistanceKm() == null || station.getDistanceKm() <= radius)
                .sorted(Comparator.comparing(s -> s.getDistanceKm() == null ? Double.MAX_VALUE : s.getDistanceKm()))
                .toList();

        return list;
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = RedisConfig.STATIONS_ALL, key = "'all'")
    public List<StationResponseDto> getAllStations() {
        return stationRepository.findAll().stream().map(station -> toResponse(station, null, null)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = RedisConfig.STATIONS_BY_OWNER, key = "'owner:' + #ownerId")
    public List<StationResponseDto> getStationsByOwner(Long ownerId) {
        Long stationOwnerId = stationOwnerRepository.findByAuthUserId(ownerId)
                .map(StationOwner::getId)
                .orElse(ownerId);
        return stationRepository.findByOwnerId(stationOwnerId).stream().map(station -> toResponse(station, null, null)).toList();
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = RedisConfig.STATIONS_BY_ID, key = "'station:' + #stationId"),
            @CacheEvict(cacheNames = {RedisConfig.STATIONS_ALL, RedisConfig.STATIONS_BY_OWNER, RedisConfig.STATIONS_NEARBY}, allEntries = true)
    })
    public void updateStationStatus(Long stationId, String status) {
        Station station = getStation(stationId);
        if (!isApproved(station)) {
            throw new BadRequestException("Station must be approved by admin before it can be activated");
        }
        station.setStatus(stationStatus(status, StationStatus.INACTIVE));
        station.setOpen(station.getStatus() == StationStatus.ACTIVE);
        stationRepository.save(station);
        notifyStationStatus(station);
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = RedisConfig.STATIONS_BY_ID, key = "'station:' + #stationId"),
            @CacheEvict(cacheNames = {RedisConfig.STATIONS_ALL, RedisConfig.STATIONS_BY_OWNER, RedisConfig.STATIONS_NEARBY}, allEntries = true)
    })
    public void suspendStation(Long stationId) {
        updateStationStatus(stationId, StationStatus.INACTIVE.name());
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = RedisConfig.STATIONS_BY_ID, key = "'station:' + #stationId"),
            @CacheEvict(cacheNames = {RedisConfig.STATIONS_ALL, RedisConfig.STATIONS_BY_OWNER, RedisConfig.STATIONS_NEARBY}, allEntries = true)
    })
    public void deleteStation(Long stationId) {
        stationRepository.delete(getStation(stationId));
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = {RedisConfig.STATIONS_ALL, RedisConfig.STATIONS_BY_OWNER, RedisConfig.STATIONS_NEARBY}, allEntries = true)
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
        if (!isAdmin()) {
            station.setApprovalStatus(StationApprovalStatus.PENDING);
            station.setStatus(StationStatus.INACTIVE);
            station.setOpen(false);
        } else {
            station.setApprovalStatus(StationApprovalStatus.APPROVED);
            station.setApprovedAt(Instant.now());
        }
        Station saved = stationRepository.save(station);
        notifyStationUpdated(saved);
        return toResponse(saved, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = RedisConfig.STATIONS_BY_OWNER, key = "'my:' + #ownerEmail")
    public List<StationResponseDto> getMyStations(String ownerEmail) {
        StationOwner owner = stationOwnerRepository.findByEmail(ownerEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Station owner profile not found"));
        return stationRepository.findByOwnerId(owner.getId()).stream().map(station -> toResponse(station, null, null)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = RedisConfig.STATIONS_BY_ID, key = "'station:' + #stationId", unless = "#result.approvalStatus != T(com.voltx.evgenee.enums.StationApprovalStatus).APPROVED")
    public StationResponseDto getStationById(Long stationId) {
        Station station = getStation(stationId);
        if (!isApproved(station) && !canViewUnapproved(station)) {
            throw new ResourceNotFoundException("Station not found or not approved yet: " + stationId);
        }
        return toResponse(station, null, null);
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = RedisConfig.STATIONS_BY_ID, key = "'station:' + #stationId"),
            @CacheEvict(cacheNames = {RedisConfig.STATIONS_ALL, RedisConfig.STATIONS_BY_OWNER, RedisConfig.STATIONS_NEARBY}, allEntries = true)
    })
    public StationResponseDto updateStation(Long stationId, StationRequestDto request) {
        Station station = getStation(stationId);
        applyRequest(station, request);
        Station saved = stationRepository.save(station);
        notifyStationUpdated(saved);
        return toResponse(saved, null, null);
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = RedisConfig.STATIONS_BY_ID, key = "'station:' + #stationId"),
            @CacheEvict(cacheNames = {RedisConfig.STATIONS_ALL, RedisConfig.STATIONS_BY_OWNER, RedisConfig.STATIONS_NEARBY}, allEntries = true)
    })
    public ReviewResponseDto addReview(Long stationId, ReviewRequestDto request) {
        Station station = getStation(stationId);
        String email = currentEmail();
        EvUser user = evUserRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("EV user profile not found"));
        Review review = reviewRepository.findByStationIdAndUserId(station.getId(), user.getId())
                .orElseGet(() -> Review.builder()
                        .station(station)
                        .user(user)
                        .createdAt(Instant.now())
                        .build());
        review.setRating(request.getRating());
        review.setComment(request.getComment());
        return toReviewResponse(reviewRepository.save(review));
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = RedisConfig.STATIONS_BY_ID, key = "'station:' + #stationId"),
            @CacheEvict(cacheNames = {RedisConfig.STATIONS_ALL, RedisConfig.STATIONS_BY_OWNER, RedisConfig.STATIONS_NEARBY}, allEntries = true)
    })
    public void toggleStationStatus(Long stationId) {
        Station station = getStation(stationId);
        if (!isApproved(station)) {
            throw new BadRequestException("Station must be approved by admin before it can be activated");
        }
        boolean open = station.getOpen() == null || !station.getOpen();
        station.setOpen(open);
        station.setStatus(open ? StationStatus.ACTIVE : StationStatus.INACTIVE);
        stationRepository.save(station);
        notifyStationStatus(station);
    }


    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = RedisConfig.STATIONS_BY_ID, key = "'station:' + #stationId"),
            @CacheEvict(cacheNames = {RedisConfig.STATIONS_ALL, RedisConfig.STATIONS_BY_OWNER, RedisConfig.STATIONS_NEARBY}, allEntries = true)
    })
    public StationResponseDto approveStation(Long stationId) {
        Station station = getStation(stationId);
        station.setApprovalStatus(StationApprovalStatus.APPROVED);
        station.setStatus(StationStatus.ACTIVE);
        station.setOpen(true);
        station.setApprovedAt(Instant.now());
        station.setApprovalNote(null);
        Station saved = stationRepository.save(station);
        notifyStationUpdated(saved);
        notifyStationStatus(saved);
        return toResponse(saved, null, null);
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = RedisConfig.STATIONS_BY_ID, key = "'station:' + #stationId"),
            @CacheEvict(cacheNames = {RedisConfig.STATIONS_ALL, RedisConfig.STATIONS_BY_OWNER, RedisConfig.STATIONS_NEARBY}, allEntries = true)
    })
    public StationResponseDto rejectStation(Long stationId, String reason) {
        Station station = getStation(stationId);
        station.setApprovalStatus(StationApprovalStatus.REJECTED);
        station.setStatus(StationStatus.INACTIVE);
        station.setOpen(false);
        station.setApprovalNote(reason == null || reason.isBlank() ? "Rejected by admin" : reason);
        Station saved = stationRepository.save(station);
        notifyStationUpdated(saved);
        notifyStationStatus(saved);
        return toResponse(saved, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StationResponseDto> getPendingStations() {
        return stationRepository.findByApprovalStatusOrderByIdDesc(StationApprovalStatus.PENDING).stream()
                .map(station -> toResponse(station, null, null))
                .toList();
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = {RedisConfig.STATIONS_BY_ID, RedisConfig.STATIONS_ALL, RedisConfig.STATIONS_BY_OWNER, RedisConfig.STATIONS_NEARBY, RedisConfig.ROADSIDE_STATIC}, allEntries = true)
    public MechanicDto addMechanic(Long stationId, MechanicDto request) {
        Station station = getOwnedStation(stationId);
        StationMechanic mechanic = StationMechanic.builder()
                .station(station)
                .name(request.getName())
                .phone(request.getPhone())
                .garage(request.getGarage())
                .speciality(request.getSpeciality())
                .rating(request.getRating() != null ? request.getRating() : 4.5)
                .active(request.getActive() == null || request.getActive())
                .build();
        return toMechanicResponse(stationMechanicRepository.save(mechanic));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MechanicDto> getMyMechanics(String ownerEmail) {
        return stationMechanicRepository.findByOwnerEmail(ownerEmail).stream()
                .map(this::toMechanicResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MechanicDto> getStationMechanics(Long stationId) {
        getOwnedStation(stationId);
        return stationMechanicRepository.findByStationIdOrderByCreatedAtDesc(stationId).stream()
                .map(this::toMechanicResponse)
                .toList();
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = {RedisConfig.STATIONS_BY_ID, RedisConfig.STATIONS_ALL, RedisConfig.STATIONS_BY_OWNER, RedisConfig.STATIONS_NEARBY, RedisConfig.ROADSIDE_STATIC}, allEntries = true)
    public MechanicDto updateMechanic(Long mechanicId, MechanicDto request) {
        StationMechanic mechanic = stationMechanicRepository.findById(mechanicId)
                .orElseThrow(() -> new ResourceNotFoundException("Mechanic not found: " + mechanicId));
        assertOwnerOrAdmin(mechanic.getStation());
        mechanic.setName(value(request.getName(), mechanic.getName()));
        mechanic.setPhone(value(request.getPhone(), mechanic.getPhone()));
        mechanic.setGarage(value(request.getGarage(), mechanic.getGarage()));
        mechanic.setSpeciality(value(request.getSpeciality(), mechanic.getSpeciality()));
        mechanic.setRating(value(request.getRating(), mechanic.getRating()));
        mechanic.setActive(value(request.getActive(), mechanic.getActive()));
        return toMechanicResponse(stationMechanicRepository.save(mechanic));
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = {RedisConfig.STATIONS_BY_ID, RedisConfig.STATIONS_ALL, RedisConfig.STATIONS_BY_OWNER, RedisConfig.STATIONS_NEARBY, RedisConfig.ROADSIDE_STATIC}, allEntries = true)
    public void deleteMechanic(Long mechanicId) {
        StationMechanic mechanic = stationMechanicRepository.findById(mechanicId)
                .orElseThrow(() -> new ResourceNotFoundException("Mechanic not found: " + mechanicId));
        assertOwnerOrAdmin(mechanic.getStation());
        stationMechanicRepository.delete(mechanic);
    }
    private void applyRequest(Station station, StationRequestDto request) {
        StationApprovalStatus existingApprovalStatus = approvalStatusOf(station);
        station.setName(value(request.getName(), station.getName()));
        station.setOwnerName(value(request.getOwnerofStation(), station.getOwnerName()));
        station.setChargersCount(value(request.getTotalPorts(), station.getChargersCount()));
        station.setAvailablePorts(value(request.getAvailablePorts(), station.getAvailablePorts()));
        station.setChargingSpeed(value(request.getChargingSpeed(), station.getChargingSpeed()));
        station.setPlatformFee(value(request.getPlatformFee(), station.getPlatformFee() != null ? station.getPlatformFee() : defaultPlatformFee));
        station.setOpen(value(request.getIsOpen(), station.getOpen() != null ? station.getOpen() : true));
        station.setOpeningHours(value(request.getOpeningHours(), station.getOpeningHours() != null ? station.getOpeningHours() : "08:00 - 22:00"));
        station.setStatus(value(request.getStatus(), station.getStatus() != null ? station.getStatus() : StationStatus.INACTIVE));
        if (station.getApprovalStatus() == null) {
            station.setApprovalStatus(existingApprovalStatus);
        }
        station.setOperator(value(request.getOperator(), station.getOperator()));

        if (request.getLocation() != null && request.getLocation().getCoordinates() != null && request.getLocation().getCoordinates().size() >= 2) {
            station.setLongitude(request.getLocation().getCoordinates().get(0));
            station.setLatitude(request.getLocation().getCoordinates().get(1));
        }
        if (request.getAddress() != null) {
            station.setAddressDetails(toStationAddress(station, request.getAddress()));
            station.setAddress(joinAddress(request.getAddress()));
        }
        if (request.getContactInfo() != null) {
            station.setContactPhone(request.getContactInfo().getPhoneNumber());
            station.setContactEmail(request.getContactInfo().getEmail());
        }
        replaceAmenities(station, request.getAmenities());
        replaceConnectors(station, request.getTypeOfConnectors());
        replacePricing(station, request.getPricing());
        replaceImages(station, request.getImages());
        replacePeakPricing(station, request.getPeakPricing());
    }

    private StationResponseDto toResponse(Station station, Double userLat, Double userLng) {
        Integer totalPorts = station.getChargersCount() != null ? station.getChargersCount() : 4;
        List<PricingDto> pricing = stationPricing(station, totalPorts);
        List<ConnectorType> connectors = stationConnectors(station, pricing);
        AddressDto address = toAddressDto(station.getAddressDetails(), fallbackAddress(station.getAddress()));
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
                .amenities(stationAmenities(station))
                .totalPorts(totalPorts)
                .availablePorts(station.getAvailablePorts() != null ? station.getAvailablePorts() : totalPorts)
                .chargingSpeed(station.getChargingSpeed() != null ? station.getChargingSpeed() : 50)
                .typeOfConnectors(connectors)
                .pricing(pricing)
                .platformFee(station.getPlatformFee() != null ? station.getPlatformFee() : defaultPlatformFee)
                .isOpen(station.getOpen() == null || station.getOpen())
                .openingHours(station.getOpeningHours() != null ? station.getOpeningHours() : "08:00 - 22:00")
                .contactInfo(ContactInfoDto.builder().phoneNumber(station.getContactPhone()).email(station.getContactEmail()).build())
                .status(station.getStatus() != null ? station.getStatus() : StationStatus.INACTIVE)
                .approvalStatus(approvalStatusOf(station))
                .approvalNote(station.getApprovalNote())
                .approvedAt(station.getApprovedAt() == null ? null : station.getApprovedAt().toString())
                .operator(station.getOperator())
                .Images(stationImages(station))
                .mechanic(firstMechanic(station))
                .mechanics(station.getMechanics() == null ? List.of() : station.getMechanics().stream()
                        .map(this::toMechanicResponse)
                        .toList())
                .reviews(station.getReviews() == null ? List.of() : station.getReviews().stream()
                        .map(r -> ReviewDto.builder()
                                .userId(r.getUser() == null ? null : String.valueOf(r.getUser().getId()))
                                .comment(r.getComment())
                                .rating(r.getRating() == null ? null : r.getRating().doubleValue())
                                .build())
                        .toList())
                .distance(distance)
                .distanceKm(distance)
                .peakPricing(stationPeakPricing(station))
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


    private Station getOwnedStation(Long stationId) {
        Station station = getStation(stationId);
        assertOwnerOrAdmin(station);
        return station;
    }

    private void assertOwnerOrAdmin(Station station) {
        if (isAdmin()) return;
        String email = currentEmail();
        String ownerEmail = station.getOwner() == null || station.getOwner().getAuthUser() == null
                ? null
                : station.getOwner().getAuthUser().getEmail();
        if (ownerEmail == null || !ownerEmail.equalsIgnoreCase(email)) {
            throw new BadRequestException("You can manage only your own station resources");
        }
    }

    private boolean isAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private boolean canViewUnapproved(Station station) {
        if (isAdmin()) return true;
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName())) {
            return false;
        }
        String ownerEmail = station.getOwner() == null || station.getOwner().getAuthUser() == null
                ? null
                : station.getOwner().getAuthUser().getEmail();
        return ownerEmail != null && ownerEmail.equalsIgnoreCase(auth.getName());
    }

    private MechanicDto toMechanicResponse(StationMechanic mechanic) {
        Station station = mechanic.getStation();
        return MechanicDto.builder()
                .id(mechanic.getId())
                .stationId(station == null ? null : station.getId())
                .stationName(station == null ? null : station.getName())
                .name(mechanic.getName())
                .phone(mechanic.getPhone())
                .garage(mechanic.getGarage())
                .rating(mechanic.getRating())
                .speciality(mechanic.getSpeciality())
                .active(mechanic.getActive())
                .build();
    }

    private MechanicDto firstMechanic(Station station) {
        if (station.getMechanics() == null || station.getMechanics().isEmpty()) {
            return null;
        }
        return toMechanicResponse(station.getMechanics().get(0));
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

    private boolean isApproved(Station station) {
        return StationApprovalStatus.APPROVED == approvalStatusOf(station);
    }

    private StationStatus stationStatus(String status, StationStatus fallback) {
        if (status == null || status.isBlank()) return fallback;
        try {
            return StationStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private StationApprovalStatus approvalStatusOf(Station station) {
        StationApprovalStatus approvalStatus = station.getApprovalStatus();
        if (approvalStatus != null) {
            return approvalStatus;
        }
        if (station.getApprovedAt() != null) return StationApprovalStatus.APPROVED;
        return station.getStatus() != null ? StationApprovalStatus.APPROVED : StationApprovalStatus.PENDING;
    }

    private List<PricingDto> defaultPricing(Integer totalPorts) {
        return List.of(
                PricingDto.builder().connectorType(ConnectorType.CCS2).priceperKWh(15.0).portCount(Math.max(1, totalPorts / 2)).currency(CurrencyCode.INR).build(),
                PricingDto.builder().connectorType(ConnectorType.TYPE2).priceperKWh(12.0).portCount(Math.max(1, totalPorts / 2)).currency(CurrencyCode.INR).build());
    }

    private StationAddress toStationAddress(Station station, AddressDto address) {
        StationAddress entity = station.getAddressDetails() == null ? new StationAddress() : station.getAddressDetails();
        entity.setStation(station);
        entity.setCity(address.getCity());
        entity.setState(address.getState());
        entity.setCountry(address.getCountry());
        entity.setPostalCode(address.getPostalCode());
        entity.setStreet(address.getStreet());
        return entity;
    }

    private AddressDto toAddressDto(StationAddress address, AddressDto fallback) {
        if (address == null) return fallback;
        return AddressDto.builder()
                .city(address.getCity())
                .state(address.getState())
                .country(address.getCountry())
                .postalCode(address.getPostalCode())
                .street(address.getStreet())
                .build();
    }

    private void replaceAmenities(Station station, List<String> amenities) {
        station.getAmenities().clear();
        if (amenities == null) return;
        amenities.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(item -> StationAmenity.builder().station(station).name(item).build())
                .forEach(station.getAmenities()::add);
    }

    private void replaceConnectors(Station station, List<ConnectorType> connectors) {
        station.getConnectors().clear();
        if (connectors == null) return;
        connectors.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .map(item -> StationConnector.builder().station(station).connectorType(item).build())
                .forEach(station.getConnectors()::add);
    }

    private void replacePricing(Station station, List<PricingDto> pricing) {
        station.getPricing().clear();
        if (pricing == null) return;
        pricing.stream()
                .filter(java.util.Objects::nonNull)
                .map(item -> StationPricing.builder()
                        .station(station)
                        .connectorType(item.getConnectorType())
                        .priceperKWh(item.getPriceperKWh())
                        .portCount(item.getPortCount())
                        .currency(item.getCurrency())
                        .build())
                .forEach(station.getPricing()::add);
    }

    private void replaceImages(Station station, List<String> images) {
        station.getImages().clear();
        if (images == null) return;
        images.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(item -> StationImage.builder().station(station).url(item).build())
                .forEach(station.getImages()::add);
    }

    private void replacePeakPricing(Station station, List<PeakPricingDto> peakPricing) {
        station.getPeakPricing().clear();
        if (peakPricing == null) return;
        peakPricing.stream()
                .filter(java.util.Objects::nonNull)
                .map(item -> StationPeakPricing.builder()
                        .station(station)
                        .startTime(item.getStartTime())
                        .endTime(item.getEndTime())
                        .multiplier(item.getMultiplier())
                        .build())
                .forEach(station.getPeakPricing()::add);
    }

    private List<String> stationAmenities(Station station) {
        if (station.getAmenities() == null) return List.of();
        return station.getAmenities().stream()
                .map(StationAmenity::getName)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private List<ConnectorType> stationConnectors(Station station, List<PricingDto> pricing) {
        if (station.getConnectors() != null && !station.getConnectors().isEmpty()) {
            return station.getConnectors().stream()
                    .map(StationConnector::getConnectorType)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
        }
        return pricing.stream().map(PricingDto::getConnectorType).filter(java.util.Objects::nonNull).toList();
    }

    private List<PricingDto> stationPricing(Station station, Integer totalPorts) {
        if (station.getPricing() == null || station.getPricing().isEmpty()) {
            return defaultPricing(totalPorts);
        }
        return station.getPricing().stream()
                .map(item -> PricingDto.builder()
                        .connectorType(item.getConnectorType())
                        .priceperKWh(item.getPriceperKWh())
                        .portCount(item.getPortCount())
                        .currency(item.getCurrency())
                        .build())
                .toList();
    }

    private List<String> stationImages(Station station) {
        if (station.getImages() == null) return List.of();
        return station.getImages().stream()
                .map(StationImage::getUrl)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private List<PeakPricingDto> stationPeakPricing(Station station) {
        if (station.getPeakPricing() == null) return List.of();
        return station.getPeakPricing().stream()
                .map(item -> PeakPricingDto.builder()
                        .startTime(item.getStartTime())
                        .endTime(item.getEndTime())
                        .multiplier(item.getMultiplier())
                        .build())
                .toList();
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
