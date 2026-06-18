package com.voltx.evgenee.socket;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class SocketPayload {
    private String stationId;
    private String userId;
    private String bookingId;
    private String status;
    private String message;
    private String timestamp;
    private Object data;
    private Long count;
    private String type;
    private String connectorType;
    private String startTime;
    private String endTime;
    private String date;
    private Boolean isOpen;
    private String name;
}
