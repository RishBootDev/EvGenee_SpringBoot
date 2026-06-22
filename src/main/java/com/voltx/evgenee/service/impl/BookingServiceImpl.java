package com.voltx.evgenee.service.impl;

import com.voltx.evgenee.dto.requests.BookingRequestDto;
import com.voltx.evgenee.dto.responses.BookingResponseDto;
import com.voltx.evgenee.entity.Booking;
import com.voltx.evgenee.entity.EvUser;
import com.voltx.evgenee.entity.Station;
import com.voltx.evgenee.entity.Vehicle;
import com.voltx.evgenee.enums.BookingStatus;
import com.voltx.evgenee.exceptions.BadRequestException;
import com.voltx.evgenee.exceptions.ResourceNotFoundException;
import com.voltx.evgenee.repository.BookingRepository;
import com.voltx.evgenee.repository.EvUserRepository;
import com.voltx.evgenee.repository.StationRepository;
import com.voltx.evgenee.repository.VehicleRepository;
import com.voltx.evgenee.notification.EmailNotificationPublisher;
import com.voltx.evgenee.service.BookingService;
import com.voltx.evgenee.service.StationService;
import com.voltx.evgenee.socket.RealtimeNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingServiceImpl implements BookingService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final BookingRepository bookingRepository;
    private final StationRepository stationRepository;
    private final EvUserRepository evUserRepository;
    private final VehicleRepository vehicleRepository;
    private final StationService stationService;
    private final RealtimeNotificationService realtimeNotificationService;
    private final EmailNotificationPublisher emailNotifications;

    @Override
    public BookingResponseDto validateBooking(BookingRequestDto requestDto) {
        BookingDraft draft = buildDraft(requestDto);
        assertAvailable(draft.station(), draft.start(), draft.end());
        return toResponse(draft.toBooking(BookingStatus.PENDING, null));
    }

    @Override
    @Transactional
    public BookingResponseDto createBooking(BookingRequestDto requestDto) {
        BookingDraft draft = buildDraft(requestDto);
        assertAvailable(draft.station(), draft.start(), draft.end());
        EvUser user = currentEvUser();
        Vehicle vehicle = findVehicle(user, requestDto.getVehicleNumber());

        Booking booking = draft.toBooking(BookingStatus.CONFIRMED, user);
        booking.setVehicle(vehicle);
        booking.setOtp(generateOtp());
        booking.setOtpExpiresAt(draft.start().plus(Duration.ofMinutes(30)));
        Booking saved = bookingRepository.save(booking);
        emailNotifications.bookingConfirmed(saved);
        notifyCreated(saved);
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Object checkAvailability(Long stationId, LocalDate bookingDate) {
        Station station = stationRepository.findById(stationId)
                .orElseThrow(() -> new ResourceNotFoundException("Station not found: " + stationId));
        LocalDate date = bookingDate != null ? bookingDate : LocalDate.now(IST);
        int total = station.getChargersCount() != null ? station.getChargersCount() : 4;
        List<Map<String, Object>> slots = buildSlots(station, date, total);
        int bestAvailable = slots.stream()
                .mapToInt(slot -> (Integer) slot.get("availableUnits"))
                .max()
                .orElse(total);
        return Map.of(
                "stationId", String.valueOf(stationId),
                "date", date.toString(),
                "totalPorts", total,
                "availablePorts", bestAvailable,
                "available", bestAvailable > 0,
                "slots", slots);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BookingResponseDto> getMyBookings() {
        return bookingRepository.findByUserEmailOrderByCreatedAtDesc(currentEmail()).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BookingResponseDto> getBookingsByStation(Long stationId) {
        return bookingRepository.findByStationIdOrderByStartTimeDesc(stationId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public BookingResponseDto getBookingById(Long bookingId) {
        return toResponse(getBooking(bookingId));
    }

    @Override
    @Transactional
    public BookingResponseDto cancelBooking(Long bookingId, String reason) {
        Booking booking = getBooking(bookingId);
        if (booking.getStatus() == BookingStatus.COMPLETED) {
            throw new BadRequestException("Completed booking cannot be cancelled");
        }
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(Instant.now());
        booking.setCancellationReason(reason);
        Booking saved = bookingRepository.save(booking);
        emailNotifications.bookingCancelled(saved);
        notifyCancelled(saved);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public BookingResponseDto checkInBooking(Long bookingId, String otp) {
        Booking booking = getBooking(bookingId);
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BadRequestException("Only confirmed bookings can be checked in");
        }
        if (booking.getOtp() != null && otp != null && !booking.getOtp().equals(otp)) {
            throw new BadRequestException("Invalid OTP");
        }
        booking.setStatus(BookingStatus.IN_PROGRESS);
        booking.setCheckedInAt(Instant.now());
        Booking saved = bookingRepository.save(booking);
        notifyCheckedIn(saved);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public BookingResponseDto completeBooking(Long bookingId) {
        Booking booking = getBooking(bookingId);
        if (booking.getStatus() != BookingStatus.IN_PROGRESS && booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BadRequestException("Booking is not active");
        }
        booking.setStatus(BookingStatus.COMPLETED);
        booking.setCompletedAt(Instant.now());
        Booking saved = bookingRepository.save(booking);
        notifyCompleted(saved);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public BookingResponseDto confirmAdvancePayment(Long bookingId) {
        Booking booking = getBooking(bookingId);
        if (booking.getStatus() != BookingStatus.PENDING) {
            return toResponse(booking);
        }
        booking.setStatus(BookingStatus.CONFIRMED);
        Booking saved = bookingRepository.save(booking);
        emailNotifications.bookingConfirmed(saved);
        notifyCapacity(saved);
        return toResponse(saved);
    }

    private BookingDraft buildDraft(BookingRequestDto request) {
        Long stationId = parseId(request.getStation(), "station");
        Station station = stationRepository.findById(stationId)
                .orElseThrow(() -> new ResourceNotFoundException("Station not found: " + stationId));
        LocalDate date = parseDate(request.getDate());
        LocalTime startTime = parseTime(request.getStartTime());
        LocalTime endTime = parseTime(request.getEndTime());
        if (!endTime.isAfter(startTime)) throw new BadRequestException("End time must be after start time");
        Instant start = ZonedDateTime.of(date, startTime, IST).toInstant();
        Instant end = ZonedDateTime.of(date, endTime, IST).toInstant();
        if (start.isBefore(Instant.now())) throw new BadRequestException("Cannot book a past time slot");

        long durationMinutes = Duration.between(start, end).toMinutes();
        double hours = durationMinutes / 60.0;
        double speed = station.getChargingSpeed() != null ? station.getChargingSpeed() : 50.0;
        double estimatedKWh = round(speed * hours);
        double price = 15.0;
        double totalCost = round(estimatedKWh * price);
        double platformFee = round(totalCost * ((station.getPlatformFee() != null ? station.getPlatformFee() : 20.0) / 100.0));
        double grandTotal = round(totalCost + platformFee);
        return new BookingDraft(station, start, end, request.getConnectorType(), request.getVehicleNumber(),
                (int) durationMinutes, estimatedKWh, totalCost, platformFee, grandTotal);
    }

    private void assertAvailable(Station station, Instant start, Instant end) {
        int blocking = (int) bookingRepository.findOverlappingBookings(station.getId(), end, start)
                .stream()
                .filter(this::isCapacityBlocking)
                .count();
        int total = station.getChargersCount() != null ? station.getChargersCount() : 4;
        if (blocking >= total) throw new BadRequestException("Selected slot is not available");
    }

    private boolean isCapacityBlocking(Booking booking) {
        return booking.getStatus() == BookingStatus.CONFIRMED
                || booking.getStatus() == BookingStatus.IN_PROGRESS
                || (booking.getStatus() == BookingStatus.PENDING
                && booking.getCreatedAt() != null
                && booking.getCreatedAt().isAfter(Instant.now().minus(Duration.ofMinutes(10))));
    }

    private List<Map<String, Object>> buildSlots(Station station, LocalDate date, int totalUnits) {
        int[] bounds = openingBounds(station.getOpeningHours());
        java.util.ArrayList<Map<String, Object>> slots = new java.util.ArrayList<>();
        for (int startMinute = bounds[0]; startMinute + 60 <= bounds[1]; startMinute += 60) {
            int endMinute = startMinute + 60;
            Instant start = ZonedDateTime.of(date, LocalTime.of(startMinute / 60, startMinute % 60), IST).toInstant();
            Instant end = ZonedDateTime.of(date, LocalTime.of(endMinute / 60, endMinute % 60), IST).toInstant();
            int active = (int) bookingRepository.findOverlappingBookings(station.getId(), end, start)
                    .stream()
                    .filter(this::isCapacityBlocking)
                    .count();
            int available = Math.max(0, totalUnits - active);
            slots.add(Map.of(
                    "startTime", minutesToTime(startMinute),
                    "endTime", minutesToTime(endMinute),
                    "isAvailable", available > 0,
                    "availableUnits", available,
                    "totalUnits", totalUnits));
        }
        return slots;
    }

    private int[] openingBounds(String openingHours) {
        if (openingHours != null && openingHours.contains("-")) {
            try {
                String[] parts = openingHours.split("-");
                return new int[]{timeToMinutes(parts[0].trim()), timeToMinutes(parts[1].trim())};
            } catch (Exception ignored) {
            }
        }
        return new int[]{8 * 60, 22 * 60};
    }

    private int timeToMinutes(String time) {
        LocalTime localTime = LocalTime.parse(time);
        return localTime.getHour() * 60 + localTime.getMinute();
    }

    private String minutesToTime(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    private BookingResponseDto toResponse(Booking booking) {
        Long id = booking.getId() == null ? null : booking.getId();
        ZonedDateTime start = booking.getStartTime() == null ? null : booking.getStartTime().atZone(IST);
        ZonedDateTime end = booking.getEndTime() == null ? null : booking.getEndTime().atZone(IST);
        String vehicleNumber = booking.getVehicleNumber();
        if ((vehicleNumber == null || vehicleNumber.isBlank()) && booking.getVehicle() != null) {
            vehicleNumber = booking.getVehicle().getLicensePlate();
        }
        return BookingResponseDto.builder()
                .id(id)
                ._id(id == null ? null : String.valueOf(id))
                .user(booking.getUser() == null ? null : String.valueOf(booking.getUser().getId()))
                .station(booking.getStation() == null || booking.getStation().getId() == null ? null : stationService.getStationById(booking.getStation().getId()))
                .connectorType(booking.getConnectorType())
                .date(start == null ? null : start.toLocalDate().toString())
                .startTime(start == null ? null : start.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")))
                .endTime(end == null ? null : end.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")))
                .durationMinutes(booking.getDurationMinutes())
                .estimatedKWh(booking.getEstimatedKWh())
                .totalCost(booking.getTotalCost())
                .platformFee(booking.getPlatformFee())
                .grandTotal(booking.getGrandTotal())
                .vehicleNumber(vehicleNumber)
                .otp(booking.getOtp())
                .status(toFrontendStatus(booking.getStatus()))
                .cancelledAt(formatInstant(booking.getCancelledAt()))
                .cancellationReason(booking.getCancellationReason())
                .checkedInAt(formatInstant(booking.getCheckedInAt()))
                .completedAt(formatInstant(booking.getCompletedAt()))
                .createdAt(formatInstant(booking.getCreatedAt()))
                .build();
    }

    private Booking getBooking(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));
    }

    private EvUser currentEvUser() {
        return evUserRepository.findByEmail(currentEmail())
                .orElseThrow(() -> new ResourceNotFoundException("EV user profile not found"));
    }

    private String currentEmail() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) throw new BadRequestException("Authenticated user required");
        return auth.getName();
    }

    private Vehicle findVehicle(EvUser user, String vehicleNumber) {
        List<Vehicle> vehicles = vehicleRepository.findByOwnerId(user.getId());
        if (vehicleNumber == null || vehicleNumber.isBlank()) return vehicles.isEmpty() ? null : vehicles.get(0);
        return vehicles.stream()
                .filter(v -> vehicleNumber.equalsIgnoreCase(v.getLicensePlate()))
                .findFirst()
                .orElse(null);
    }

    private Long parseId(String id, String field) {
        try {
            return Long.parseLong(id);
        } catch (Exception e) {
            throw new BadRequestException("Invalid " + field + " id");
        }
    }

    private LocalDate parseDate(String date) {
        if (date == null || date.isBlank() || date.equalsIgnoreCase("today")) return LocalDate.now(IST);
        return LocalDate.parse(date);
    }

    private LocalTime parseTime(String time) {
        if (time == null || time.isBlank()) throw new BadRequestException("Booking time is required");
        return LocalTime.parse(time);
    }

    private String generateOtp() {
        return String.valueOf(100000 + new Random().nextInt(900000));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String toFrontendStatus(BookingStatus status) {
        if (status == null) return "pending";
        return status.name().toLowerCase().replace("_", "-");
    }

    private String formatInstant(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private void notifyCreated(Booking booking) {
        try {
            ZonedDateTime start = booking.getStartTime().atZone(IST);
            ZonedDateTime end = booking.getEndTime().atZone(IST);
            realtimeNotificationService.notifyBookingCreated(
                    String.valueOf(booking.getStation().getId()),
                    booking.getUser().getAuthUser().getEmail(),
                    String.valueOf(booking.getId()),
                    booking.getConnectorType(),
                    start.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")),
                    end.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")),
                    start.toLocalDate().toString());
            notifyCapacity(booking);
        } catch (Exception e) {
            log.warn("Unable to emit booking created event: {}", e.getMessage());
        }
    }

    private void notifyCancelled(Booking booking) {
        try {
            ZonedDateTime start = booking.getStartTime().atZone(IST);
            ZonedDateTime end = booking.getEndTime().atZone(IST);
            realtimeNotificationService.notifyBookingCancelled(
                    String.valueOf(booking.getStation().getId()),
                    booking.getUser().getAuthUser().getEmail(),
                    String.valueOf(booking.getId()),
                    start.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")),
                    end.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")),
                    start.toLocalDate().toString());
            notifyCapacity(booking);
        } catch (Exception e) {
            log.warn("Unable to emit booking cancelled event: {}", e.getMessage());
        }
    }

    private void notifyCheckedIn(Booking booking) {
        try {
            realtimeNotificationService.notifyCheckedIn(
                    String.valueOf(booking.getStation().getId()),
                    booking.getUser().getAuthUser().getEmail(),
                    String.valueOf(booking.getId()),
                    booking.getCheckedInAt());
            notifyCapacity(booking);
        } catch (Exception e) {
            log.warn("Unable to emit booking check-in event: {}", e.getMessage());
        }
    }

    private void notifyCompleted(Booking booking) {
        try {
            realtimeNotificationService.notifyCompleted(
                    String.valueOf(booking.getStation().getId()),
                    booking.getUser().getAuthUser().getEmail(),
                    String.valueOf(booking.getId()),
                    booking.getCompletedAt());
            notifyCapacity(booking);
        } catch (Exception e) {
            log.warn("Unable to emit booking completed event: {}", e.getMessage());
        }
    }

    private void notifyCapacity(Booking booking) {
        try {
            Station station = booking.getStation();
            long active = bookingRepository.findOverlappingBookings(station.getId(), booking.getEndTime(), booking.getStartTime())
                    .stream()
                    .filter(this::isCapacityBlocking)
                    .count();
            realtimeNotificationService.notifyAvailabilityUpdated(
                    String.valueOf(station.getId()),
                    booking.getStartTime().atZone(IST).toLocalDate().toString(),
                    active,
                    station.getChargersCount() != null ? station.getChargersCount() : 4);
        } catch (Exception e) {
            log.warn("Unable to emit capacity event: {}", e.getMessage());
        }
    }

    private record BookingDraft(
            Station station,
            Instant start,
            Instant end,
            String connectorType,
            String vehicleNumber,
            Integer durationMinutes,
            Double estimatedKWh,
            Double totalCost,
            Double platformFee,
            Double grandTotal) {
        Booking toBooking(BookingStatus status, EvUser user) {
            return Booking.builder()
                    .station(station)
                    .user(user)
                    .startTime(start)
                    .endTime(end)
                    .connectorType(connectorType)
                    .vehicleNumber(vehicleNumber)
                    .durationMinutes(durationMinutes)
                    .estimatedKWh(estimatedKWh)
                    .totalCost(totalCost)
                    .platformFee(platformFee)
                    .grandTotal(grandTotal)
                    .status(status)
                    .createdAt(Instant.now())
                    .build();
        }
    }
}
