package com.voltx.evgenee.dto.responses;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VehicleResponseDto {
    private String id;

    @JsonProperty("_id")
    private String _id;
    private String nickname;
    private String type;
    private String connectorType;
    private Double batteryCapacity;
    private String vehicleNumber;
}
