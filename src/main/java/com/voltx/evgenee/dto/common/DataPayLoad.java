package com.voltx.evgenee.dto.common;

import com.voltx.evgenee.dto.responses.RazorpayCheckoutDto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DataPayLoad {
    private String response;
    private String threadId;
    private String bookingId;
    private Boolean redirect;
    private Object stations;
    private RazorpayCheckoutDto checkout;
}
