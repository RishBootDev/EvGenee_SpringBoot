package com.voltx.evgenee.dto.requests;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentRequestDto {
    private Long bookingId;
    private BigDecimal amount;
    private String currency;
    private String orderId;
    private String paymentId;
    private String razorpaySignature;
    private String signature;
    private String paymentSignature;
    private String status;
    private String transactionId;
    private String method;
}
