package com.voltx.evgenee.dto.responses;

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
    private String _id;
    private Object user;
    private Object station;
    private String connectorType;
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
    private String status;
    private String cancelledAt;
    private String cancellationReason;
    private String checkedInAt;
    private String completedAt;
    private String createdAt;
}
