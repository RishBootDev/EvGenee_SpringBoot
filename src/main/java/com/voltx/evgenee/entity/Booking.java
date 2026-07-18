package com.voltx.evgenee.entity;

import com.voltx.evgenee.enums.BookingStatus;
import com.voltx.evgenee.enums.ConnectorType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "bookings")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private EvUser user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "station_id")
    private Station station;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id")
    private Vehicle vehicle;

    private Instant startTime;
    private Instant endTime;
    @Enumerated(EnumType.STRING)
    private ConnectorType connectorType;
    private String vehicleNumber;
    private Integer durationMinutes;
    private Double estimatedKWh;
    private Double totalCost;
    private Double platformFee;
    private Double grandTotal;

    @Enumerated(EnumType.STRING)
    private BookingStatus status;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL)
    @Builder.Default
    private List<Payment> payments = new ArrayList<>();

    private Instant completedAt;
    private Instant cancelledAt;
    private String cancellationReason;
    private Instant checkedInAt;
    private String otp;
    private Instant otpExpiresAt;
    @Builder.Default
    private boolean reminderSent = false;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;
}
