package com.voltx.evgenee.service;

import com.voltx.evgenee.dto.responses.AdminDashboardResponseDto;
import com.voltx.evgenee.dto.responses.SosResponseDto;
import com.voltx.evgenee.dto.responses.StationOwnerResponseDto;
import com.voltx.evgenee.dto.responses.StationResponseDto;
import com.voltx.evgenee.dto.responses.UserResponseDto;

import java.util.List;

public interface AdminService {
    AdminDashboardResponseDto getDashboard();

    List<UserResponseDto> getUsers();

    List<StationOwnerResponseDto> getStationOwners();

    List<StationResponseDto> getPendingStations();

    StationResponseDto approveStation(Long stationId);

    StationResponseDto rejectStation(Long stationId, String reason);

    List<SosResponseDto> getRoadsideRequests();

    SosResponseDto updateRoadsideStatus(Long requestId, String status);
}