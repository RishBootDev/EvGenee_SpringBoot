package com.voltx.evgenee.dto.requests;

import com.voltx.evgenee.enums.ConnectorType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingRequestDto {
    private String station;
    private ConnectorType connectorType;
    private String date;
    private String startTime;
    private String endTime;
    private String vehicleNumber;
}
