package com.voltx.evgenee.controller;

import com.voltx.evgenee.dto.responses.AdminDashboardResponseDto;
import com.voltx.evgenee.dto.responses.ApiResponse;
import com.voltx.evgenee.dto.responses.SosResponseDto;
import com.voltx.evgenee.dto.responses.StationOwnerResponseDto;
import com.voltx.evgenee.dto.responses.StationResponseDto;
import com.voltx.evgenee.dto.responses.UserResponseDto;
import com.voltx.evgenee.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<AdminDashboardResponseDto>> getDashboard() {
        return ResponseEntity.ok(ApiResponse.ok(adminService.getDashboard()));
    }

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<List<UserResponseDto>>> getUsers() {
        return ResponseEntity.ok(ApiResponse.ok(adminService.getUsers()));
    }

    @GetMapping("/station-owners")
    public ResponseEntity<ApiResponse<List<StationOwnerResponseDto>>> getStationOwners() {
        return ResponseEntity.ok(ApiResponse.ok(adminService.getStationOwners()));
    }

    @GetMapping("/stations/pending")
    public ResponseEntity<ApiResponse<List<StationResponseDto>>> getPendingStations() {
        return ResponseEntity.ok(ApiResponse.ok(adminService.getPendingStations()));
    }

    @PatchMapping("/stations/{stationId}/approve")
    public ResponseEntity<ApiResponse<StationResponseDto>> approveStation(@PathVariable Long stationId) {
        return ResponseEntity.ok(ApiResponse.ok("Station approved", adminService.approveStation(stationId)));
    }

    @PatchMapping("/stations/{stationId}/reject")
    public ResponseEntity<ApiResponse<StationResponseDto>> rejectStation(
            @PathVariable Long stationId,
            @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(ApiResponse.ok("Station rejected", adminService.rejectStation(stationId, reason)));
    }

    @GetMapping("/roadside")
    public ResponseEntity<ApiResponse<List<SosResponseDto>>> getRoadsideRequests() {
        return ResponseEntity.ok(ApiResponse.ok(adminService.getRoadsideRequests()));
    }

    @PatchMapping("/roadside/{requestId}/status")
    public ResponseEntity<ApiResponse<SosResponseDto>> updateRoadsideStatus(
            @PathVariable Long requestId,
            @RequestParam String status) {
        return ResponseEntity.ok(ApiResponse.ok("Roadside request updated", adminService.updateRoadsideStatus(requestId, status)));
    }
}