package com.voltx.evgenee.dto.requests;

import com.voltx.evgenee.enums.CurrencyCode;
import com.voltx.evgenee.enums.PaymentMethod;
import com.voltx.evgenee.enums.RazorpayStatus;
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
    private CurrencyCode currency;
    private String orderId;
    private String paymentId;
    private String razorpaySignature;
    private String signature;
    private String paymentSignature;
    private RazorpayStatus status;
    private String transactionId;
    private PaymentMethod method;
}
