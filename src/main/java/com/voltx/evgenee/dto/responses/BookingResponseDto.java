package com.voltx.evgenee.dto.responses;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.voltx.evgenee.enums.BookingStatus;
import com.voltx.evgenee.enums.ConnectorType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingResponseDto {
    private Long id;

    @JsonProperty("_id")
    private String _id;
    private Object user;
    private Object station;
    private ConnectorType connectorType;
    private String date;
    private String startTime;
    private String endTime;
    private Integer durationMinutes;
    private Double estimatedKWh;
    private Double totalCost;
    private Double platformFee;
    private Double grandTotal;
    private String vehicleNumber;
    private String otp;
    private BookingStatus status;
    private String cancelledAt;
    private String cancellationReason;
    private String checkedInAt;
    private String completedAt;
    private String createdAt;
}
