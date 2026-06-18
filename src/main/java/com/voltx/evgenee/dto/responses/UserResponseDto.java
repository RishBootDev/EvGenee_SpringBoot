package com.voltx.evgenee.dto.responses;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserResponseDto {
    private String id;

    @JsonProperty("_id")
    private String _id;
    private String name;
    private String email;
    private String role;
    private VehicleResponseDto vehicle;
    private List<String> vehicleNumbers;
    private List<VehicleResponseDto> savedVehicles;
    private String createdAt;
}
