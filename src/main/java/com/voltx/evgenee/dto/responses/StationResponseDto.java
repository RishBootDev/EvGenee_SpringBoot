package com.voltx.evgenee.dto.responses;

import com.voltx.evgenee.dto.common.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StationResponseDto {
    private Long id;
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
