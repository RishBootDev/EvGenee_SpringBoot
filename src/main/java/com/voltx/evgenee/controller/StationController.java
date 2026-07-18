package com.voltx.evgenee.controller;


import com.voltx.evgenee.dto.common.MechanicDto;
import com.voltx.evgenee.dto.requests.ReviewRequestDto;
import com.voltx.evgenee.dto.requests.StationRequestDto;
import com.voltx.evgenee.dto.responses.ApiResponse;
import com.voltx.evgenee.dto.responses.ReviewResponseDto;
import com.voltx.evgenee.dto.responses.StationResponseDto;
import com.voltx.evgenee.service.StationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/stations")
@RequiredArgsConstructor
public class StationController {

    private final StationService stationService;


    @GetMapping("/nearby")
    public ResponseEntity<ApiResponse<List<StationResponseDto>>> getNearbyStations(
            @RequestParam(required = false) Double latitude,
            @RequestParam(required = false, name = "lat") Double lat,
            @RequestParam(required = false) Double longitude,
            @RequestParam(required = false, name = "lng") Double lng,
            @RequestParam(defaultValue = "10", required = false) Double radius,
            @RequestParam(required = false, name = "maxDistance") Double maxDistance) {

        Double finalLat = latitude != null ? latitude : lat;
        Double finalLng = longitude != null ? longitude : lng;
        Double finalRadius = maxDistance != null ? maxDistance / 1000.0 : radius;
        return ResponseEntity.ok(ApiResponse.ok(
                stationService.getNearbyStations(
                        finalLat,
                        finalLng,
                        finalRadius)));
    }

    @GetMapping("/{stationId}")
    public ResponseEntity<ApiResponse<StationResponseDto>> getStationById(
            @PathVariable String stationId) {

        return ResponseEntity.ok(ApiResponse.ok(stationService.getStationById(Long.parseLong(stationId))));
    }

    @PostMapping("/{stationId}/review")
    public ResponseEntity<ApiResponse<ReviewResponseDto>> addReview(
            @PathVariable String stationId,
            @RequestBody ReviewRequestDto request) {

        return ResponseEntity.ok(ApiResponse.ok(
                stationService.addReview(
                        Long.parseLong(stationId),
                        request)));
    }


    @PostMapping("/add")
    public ResponseEntity<ApiResponse<StationResponseDto>> addStation(
            @RequestBody StationRequestDto request) {

        return ResponseEntity.ok(ApiResponse.ok(stationService.addStation(request)));
    }

    @GetMapping("/owner/my-stations")
    public ResponseEntity<ApiResponse<List<StationResponseDto>>> getMyStations(
            Authentication authentication) {

        return ResponseEntity.ok(ApiResponse.ok(
                stationService.getMyStations(
                        authentication.getName())));
    }


    @GetMapping("/owner/mechanics")
    public ResponseEntity<ApiResponse<List<MechanicDto>>> getMyMechanics(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.ok(stationService.getMyMechanics(authentication.getName())));
    }

    @GetMapping("/{stationId}/mechanics")
    public ResponseEntity<ApiResponse<List<MechanicDto>>> getStationMechanics(@PathVariable Long stationId) {
        return ResponseEntity.ok(ApiResponse.ok(stationService.getStationMechanics(stationId)));
    }

    @PostMapping("/{stationId}/mechanics")
    public ResponseEntity<ApiResponse<MechanicDto>> addMechanic(
            @PathVariable Long stationId,
            @RequestBody MechanicDto request) {
        return ResponseEntity.ok(ApiResponse.ok("Mechanic registered", stationService.addMechanic(stationId, request)));
    }

    @PutMapping("/mechanics/{mechanicId}")
    public ResponseEntity<ApiResponse<MechanicDto>> updateMechanic(
            @PathVariable Long mechanicId,
            @RequestBody MechanicDto request) {
        return ResponseEntity.ok(ApiResponse.ok("Mechanic updated", stationService.updateMechanic(mechanicId, request)));
    }

    @DeleteMapping("/mechanics/{mechanicId}")
    public ResponseEntity<ApiResponse<Void>> deleteMechanic(@PathVariable Long mechanicId) {
        stationService.deleteMechanic(mechanicId);
        return ResponseEntity.ok(ApiResponse.ok("Mechanic deleted", null));
    }
    @PutMapping("/{stationId}")
    public ResponseEntity<ApiResponse<StationResponseDto>> updateStation(
            @PathVariable String stationId,
            @RequestBody StationRequestDto request) {

        return ResponseEntity.ok(ApiResponse.ok(
                stationService.updateStation(
                        Long.parseLong(stationId),
                        request)));
    }

    @PatchMapping("/{stationId}/toggle")
    public ResponseEntity<String> toggleStationStatus(
            @PathVariable Long stationId) {

        stationService.toggleStationStatus(stationId);

        return ResponseEntity.ok(
                "Station status updated successfully");
    }

    @GetMapping("/admin/all-stations")
    public ResponseEntity<ApiResponse<List<StationResponseDto>>> getAllStations() {

        return ResponseEntity.ok(ApiResponse.ok(stationService.getAllStations()));
    }

    @GetMapping("/admin/owner/{ownerId}")
    public ResponseEntity<ApiResponse<List<StationResponseDto>>> getStationsByOwner(
            @PathVariable Long ownerId) {

        return ResponseEntity.ok(ApiResponse.ok(stationService.getStationsByOwner(ownerId)));
    }

    @PutMapping("/admin/{stationId}/status")
    public ResponseEntity<String> updateStationStatus(
            @PathVariable String stationId,
            @RequestParam String status) {

        stationService.updateStationStatus(
                Long.parseLong(stationId),
                status);

        return ResponseEntity.ok(
                "Station status updated successfully");
    }

    @PutMapping("/admin/{stationId}/suspend")
    public ResponseEntity<String> suspendStation(
            @PathVariable String stationId) {

        stationService.suspendStation(Long.parseLong(stationId));

        return ResponseEntity.ok(
                "Station suspended successfully");
    }

    @DeleteMapping("/admin/{stationId}")
    public ResponseEntity<String> deleteStation(
            @PathVariable String stationId) {

        stationService.deleteStation(Long.parseLong(stationId));

        return ResponseEntity.ok(
                "Station deleted successfully");
    }
}
