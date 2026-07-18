package com.voltx.evgenee.dto.responses;

import com.voltx.evgenee.enums.CurrencyCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RazorpayCheckoutDto {
    private String keyId;
    private String orderId;
    private BigDecimal amount;
    private CurrencyCode currency;
    private Long bookingId;
    private String description;
}
