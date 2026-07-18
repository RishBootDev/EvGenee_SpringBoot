package com.voltx.evgenee.service;

import com.voltx.evgenee.dto.common.MechanicDto;
import com.voltx.evgenee.dto.requests.ReviewRequestDto;
import com.voltx.evgenee.dto.requests.StationRequestDto;
import com.voltx.evgenee.dto.responses.ReviewResponseDto;
import com.voltx.evgenee.dto.responses.StationResponseDto;

import java.util.List;

public interface StationService {

    List<StationResponseDto> getNearbyStations(
            Double latitude,
            Double longitude,
            Double radius);

    List<StationResponseDto> getAllStations();

    List<StationResponseDto> getStationsByOwner(
            Long ownerId);

    void updateStationStatus(
            Long stationId,
            String status);

    void suspendStation(
            Long stationId);

    void deleteStation(
            Long stationId);

    StationResponseDto addStation(
            StationRequestDto request);

    List<StationResponseDto> getMyStations(
            String ownerEmail);

    StationResponseDto getStationById(
            Long stationId);

    StationResponseDto updateStation(
            Long stationId,
            StationRequestDto request);

    ReviewResponseDto addReview(
            Long stationId,
            ReviewRequestDto request);

    void toggleStationStatus(
            Long stationId);

    StationResponseDto approveStation(Long stationId);

    StationResponseDto rejectStation(Long stationId, String reason);

    List<StationResponseDto> getPendingStations();

    MechanicDto addMechanic(Long stationId, MechanicDto request);

    List<MechanicDto> getMyMechanics(String ownerEmail);

    List<MechanicDto> getStationMechanics(Long stationId);

    MechanicDto updateMechanic(Long mechanicId, MechanicDto request);

    void deleteMechanic(Long mechanicId);
}
