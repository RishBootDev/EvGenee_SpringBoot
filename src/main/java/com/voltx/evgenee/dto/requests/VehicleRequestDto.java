package com.voltx.evgenee.dto.requests;

import com.voltx.evgenee.enums.ConnectorType;
import com.voltx.evgenee.enums.VehicleType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VehicleRequestDto {
    private String nickname;
    private VehicleType type;
    private ConnectorType connectorType;
    private Double batteryCapacity;
    private String vehicleNumber;
}
