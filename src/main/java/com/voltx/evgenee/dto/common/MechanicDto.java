package com.voltx.evgenee.dto.common;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MechanicDto {
    private Long id;
    private Long stationId;
    private String stationName;
    private String name;
    private String phone;
    private String garage;
    private Double rating;
    private String speciality;
    private Boolean active;
}