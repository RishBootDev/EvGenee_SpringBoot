package com.voltx.evgenee.controller;


import com.voltx.evgenee.dto.requests.BookingRequestDto;
import com.voltx.evgenee.dto.responses.ApiResponse;
import com.voltx.evgenee.dto.responses.BookingResponseDto;
import com.voltx.evgenee.service.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    @PostMapping("/validate")
    public ResponseEntity<ApiResponse<BookingResponseDto>> validateBooking(
            @RequestBody BookingRequestDto requestDto) {

        return ResponseEntity.ok(ApiResponse.ok(bookingService.validateBooking(requestDto)));
    }

    @PostMapping("/create")
    public ResponseEntity<ApiResponse<BookingResponseDto>> createBooking(
            @RequestBody BookingRequestDto requestDto) {

        return ResponseEntity.ok(ApiResponse.ok(bookingService.createBooking(requestDto)));
    }


    @GetMapping("/availability")
    public ResponseEntity<ApiResponse<Object>> checkAvailability(
            @RequestParam Long stationId,
            @RequestParam(required = false) LocalDate bookingDate,
            @RequestParam(required = false) LocalDate date) {

        return ResponseEntity.ok(ApiResponse.ok(
                bookingService.checkAvailability(stationId, bookingDate != null ? bookingDate : date)
        ));
    }

    @GetMapping("/my-bookings")
    public ResponseEntity<ApiResponse<List<BookingResponseDto>>> getMyBookings() {

        return ResponseEntity.ok(ApiResponse.ok(bookingService.getMyBookings()));
    }

    @GetMapping("/station/{stationId}")
    public ResponseEntity<ApiResponse<List<BookingResponseDto>>> getBookingsByStation(
            @PathVariable Long stationId) {

        return ResponseEntity.ok(ApiResponse.ok(bookingService.getBookingsByStation(stationId)));
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<ApiResponse<BookingResponseDto>> getBookingById(
            @PathVariable Long bookingId) {

        return ResponseEntity.ok(ApiResponse.ok(bookingService.getBookingById(bookingId)));
    }

    @PostMapping("/{bookingId}/cancel")
    public ResponseEntity<ApiResponse<BookingResponseDto>> cancelBooking(
            @PathVariable Long bookingId,
            @RequestBody(required = false) java.util.Map<String, String> body) {

        String reason = body != null ? body.getOrDefault("reason", "User cancelled") : "User cancelled";
        return ResponseEntity.ok(ApiResponse.ok(bookingService.cancelBooking(bookingId, reason)));
    }

    @PostMapping("/{bookingId}/check-in")
    public ResponseEntity<ApiResponse<BookingResponseDto>> checkInBooking(
            @PathVariable Long bookingId,
            @RequestBody(required = false) java.util.Map<String, String> body) {

        String otp = body != null ? body.get("otp") : null;
        return ResponseEntity.ok(ApiResponse.ok(bookingService.checkInBooking(bookingId, otp)));
    }

    @PostMapping("/{bookingId}/complete")
    public ResponseEntity<ApiResponse<BookingResponseDto>> completeBooking(
            @PathVariable Long bookingId) {

        return ResponseEntity.ok(ApiResponse.ok(bookingService.completeBooking(bookingId)));
    }

    @PostMapping("/{bookingId}/confirm-advance")
    public ResponseEntity<ApiResponse<BookingResponseDto>> confirmAdvancePayment(
            @PathVariable Long bookingId) {

        return ResponseEntity.ok(ApiResponse.ok(bookingService.confirmAdvancePayment(bookingId)));
    }
}
