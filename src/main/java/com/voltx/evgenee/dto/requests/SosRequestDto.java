package com.voltx.evgenee.dto.requests;

import com.voltx.evgenee.enums.RoadsideIssueType;
import com.voltx.evgenee.enums.SupportProvider;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SosRequestDto {
    private Double latitude;
    private Double longitude;
    private String address;
    private RoadsideIssueType issueType;
    private String description;
    private Boolean requestTow;
    private SupportProvider supportProvider;
    private Long stationId;
}
