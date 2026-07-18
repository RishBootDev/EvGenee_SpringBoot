package com.voltx.evgenee.service.impl;

import com.voltx.evgenee.dto.responses.AdminDashboardResponseDto;
import com.voltx.evgenee.dto.responses.SosResponseDto;
import com.voltx.evgenee.dto.responses.StationOwnerResponseDto;
import com.voltx.evgenee.dto.responses.StationResponseDto;
import com.voltx.evgenee.dto.responses.UserResponseDto;
import com.voltx.evgenee.entity.EvUser;
import com.voltx.evgenee.entity.StationOwner;
import com.voltx.evgenee.entity.User;
import com.voltx.evgenee.enums.Role;
import com.voltx.evgenee.repository.EvUserRepository;
import com.voltx.evgenee.repository.RoadsideRequestRepository;
import com.voltx.evgenee.repository.StationOwnerRepository;
import com.voltx.evgenee.repository.StationRepository;
import com.voltx.evgenee.repository.UserRepository;
import com.voltx.evgenee.service.AdminService;
import com.voltx.evgenee.service.RoadsideService;
import com.voltx.evgenee.service.StationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    private final UserRepository userRepository;
    private final EvUserRepository evUserRepository;
    private final StationOwnerRepository stationOwnerRepository;
    private final StationRepository stationRepository;
    private final RoadsideRequestRepository roadsideRequestRepository;
    private final StationService stationService;
    private final RoadsideService roadsideService;

    @Override
    @Transactional(readOnly = true)
    public AdminDashboardResponseDto getDashboard() {
        List<UserResponseDto> users = getUsers();
        List<StationOwnerResponseDto> owners = getStationOwners();
        List<StationResponseDto> pending = getPendingStations();
        List<SosResponseDto> roadside = getRoadsideRequests();
        return AdminDashboardResponseDto.builder()
                .totalUsers(userRepository.countByRole(Role.EV_USER))
                .totalStationOwners(userRepository.countByRole(Role.STATION_OWNER))
                .totalStations(stationRepository.count())
                .pendingStations(pending.size())
                .roadsideRequests(roadsideRequestRepository.count())
                .users(users)
                .stationOwners(owners)
                .pendingStationApprovals(pending)
                .roadsideAssistance(roadside)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserResponseDto> getUsers() {
        return userRepository.findByRole(Role.EV_USER).stream()
                .map(this::toUserResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StationOwnerResponseDto> getStationOwners() {
        return stationOwnerRepository.findAll().stream()
                .map(owner -> StationOwnerResponseDto.builder()
                        .id(owner.getId())
                        .name(owner.getName())
                        .contact(owner.getContact())
                        .authUser(owner.getAuthUser() == null ? null : toUserResponse(owner.getAuthUser()))
                        .stations(stationService.getStationsByOwner(owner.getId()))
                        .build())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StationResponseDto> getPendingStations() {
        return stationService.getPendingStations();
    }

    @Override
    public StationResponseDto approveStation(Long stationId) {
        return stationService.approveStation(stationId);
    }

    @Override
    public StationResponseDto rejectStation(Long stationId, String reason) {
        return stationService.rejectStation(stationId, reason);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SosResponseDto> getRoadsideRequests() {
        return roadsideService.getAllRequests();
    }

    @Override
    public SosResponseDto updateRoadsideStatus(Long requestId, String status) {
        return roadsideService.updateRequestStatus(requestId, status);
    }

    private UserResponseDto toUserResponse(User user) {
        String name = null;
        if (user.getRole() == Role.EV_USER) {
            name = evUserRepository.findByEmail(user.getEmail()).map(EvUser::getFullName).orElse(null);
        } else if (user.getRole() == Role.STATION_OWNER) {
            name = stationOwnerRepository.findByEmail(user.getEmail()).map(StationOwner::getName).orElse(null);
        }
        return UserResponseDto.builder()
                .id(String.valueOf(user.getId()))
                ._id(String.valueOf(user.getId()))
                .name(name)
                .email(user.getEmail())
                .role(user.getRole())
                .build();
    }
}
