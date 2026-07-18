package com.voltx.evgenee.controller;

import com.voltx.evgenee.dto.requests.SosRequestDto;
import com.voltx.evgenee.dto.responses.ApiResponse;
import com.voltx.evgenee.dto.responses.SosResponseDto;
import com.voltx.evgenee.service.RoadsideService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/roadside")
@RequiredArgsConstructor
public class RoadsideController {

    private final RoadsideService roadsideService;

    @GetMapping("/issue-types")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> getIssueTypes() {

        List<Map<String, String>> data = roadsideService.getIssueTypes().stream()
                .map(v -> Map.of("value", v, "label", v))
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    @GetMapping("/nearest-mechanic")
    public ResponseEntity<ApiResponse<com.voltx.evgenee.dto.responses.SosResponseDto.MechanicDto>> getNearestMechanic(
            @RequestParam(required = false) Double latitude,
            @RequestParam(required = false, name = "lat") Double lat,
            @RequestParam(required = false) Double longitude,
            @RequestParam(required = false, name = "lng") Double lng) {

        return ResponseEntity.ok(ApiResponse.ok(roadsideService.getNearestMechanic(
                latitude != null ? latitude : lat,
                longitude != null ? longitude : lng)));
    }

    @PostMapping("/sos")
    public ResponseEntity<ApiResponse<SosResponseDto>> createSOSRequest(
            @RequestBody SosRequestDto requestDto) {

        return ResponseEntity.ok(ApiResponse.ok("SOS request created", roadsideService.createSOSRequest(requestDto)));
    }


    @GetMapping("/my-requests")
    public ResponseEntity<ApiResponse<List<SosResponseDto>>> getMyRequests() {

        return ResponseEntity.ok(ApiResponse.ok(roadsideService.getMyRequests()));
    }


    @GetMapping("/station/requests")
    public ResponseEntity<ApiResponse<List<SosResponseDto>>> getStationRequests(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.ok(roadsideService.getStationRequests(authentication.getName())));
    }

    @PatchMapping("/station/requests/{requestId}/status")
    public ResponseEntity<ApiResponse<SosResponseDto>> updateStationRequestStatus(
            @PathVariable Long requestId,
            @RequestParam String status,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.ok(
                "SOS request updated",
                roadsideService.updateStationRequestStatus(authentication.getName(), requestId, status)));
    }

    @GetMapping("/admin/requests")
    public ResponseEntity<ApiResponse<List<SosResponseDto>>> getAllRequests() {
        return ResponseEntity.ok(ApiResponse.ok(roadsideService.getAllRequests()));
    }

    @PatchMapping("/admin/requests/{requestId}/status")
    public ResponseEntity<ApiResponse<SosResponseDto>> updateAdminRequestStatus(
            @PathVariable Long requestId,
            @RequestParam String status) {
        return ResponseEntity.ok(ApiResponse.ok("SOS request updated", roadsideService.updateRequestStatus(requestId, status)));
    }
    @GetMapping("/sos/{requestId}")
    public ResponseEntity<ApiResponse<SosResponseDto>> getRequestDetails(
            @PathVariable Long requestId) {

        return ResponseEntity.ok(ApiResponse.ok(roadsideService.getRequestDetails(requestId)));
    }


    @PatchMapping("/sos/{requestId}/cancel")
    public ResponseEntity<ApiResponse<SosResponseDto>> cancelRequest(
            @PathVariable Long requestId) {

        return ResponseEntity.ok(ApiResponse.ok("SOS request cancelled", roadsideService.cancelRequest(requestId)));
    }
}
