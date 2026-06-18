package com.voltx.evgenee.dto.responses;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.voltx.evgenee.dto.common.*;
import lombok.*;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder
public class StationResponseDto {
    private Long id;

    @JsonProperty("_id")
    private String _id;

    private String name;
    private Object ownerofStation;
    private LocationDto location;
    private AddressDto address;
    private List<String> amenities;
    private Integer totalPorts;
    private Integer availablePorts;
    private Integer chargingSpeed;
    private List<String> typeOfConnectors;
    private List<PricingDto> pricing;
    private Double platformFee;
    private Boolean isOpen;
    private String openingHours;
    private ContactInfoDto contactInfo;
    private String status;
    private String operator;
    private List<String> Images;
    private MechanicDto mechanic;
    private List<ReviewDto> reviews;
    private Double distance;
    private Double distanceKm;
    private List<PeakPricingDto> peakPricing;

}
