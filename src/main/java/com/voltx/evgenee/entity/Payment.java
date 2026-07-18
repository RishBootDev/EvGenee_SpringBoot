package com.voltx.evgenee.entity;

import com.voltx.evgenee.enums.CurrencyCode;
import com.voltx.evgenee.enums.PaymentMethod;
import com.voltx.evgenee.enums.PaymentStatus;
import com.voltx.evgenee.enums.RazorpayStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payments")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    private String transactionId;
    @Column(unique = true)
    private String orderId;
    @Enumerated(EnumType.STRING)
    private CurrencyCode currency;
    private String receipt;
    private String razorpaySignature;
    @Enumerated(EnumType.STRING)
    private RazorpayStatus razorpayStatus;

    private Instant paidAt;
}
