package com.voltx.evgenee.dto.common;


import com.voltx.evgenee.enums.ConnectorType;
import com.voltx.evgenee.enums.CurrencyCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PricingDto {
    private Double priceperKWh;
    private ConnectorType connectorType;
    private Integer portCount;
    private CurrencyCode currency;
}
