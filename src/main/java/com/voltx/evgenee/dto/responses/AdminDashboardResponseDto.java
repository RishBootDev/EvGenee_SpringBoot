package com.voltx.evgenee.dto.responses;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminDashboardResponseDto {
    private long totalUsers;
    private long totalStationOwners;
    private long totalStations;
    private long pendingStations;
    private long roadsideRequests;
    private List<UserResponseDto> users;
    private List<StationOwnerResponseDto> stationOwners;
    private List<StationResponseDto> pendingStationApprovals;
    private List<SosResponseDto> roadsideAssistance;
}
