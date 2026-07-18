package com.voltx.evgenee.entity;

import com.voltx.evgenee.enums.ApprovalRequiredBy;
import com.voltx.evgenee.enums.RoadsideIssueType;
import com.voltx.evgenee.enums.RoadsideStatus;
import com.voltx.evgenee.enums.SupportProvider;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "roadside_requests")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoadsideRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private RoadsideStatus status;

    @Enumerated(EnumType.STRING)
    private SupportProvider supportProvider;

    @Enumerated(EnumType.STRING)
    private ApprovalRequiredBy approvalRequiredBy;

    @Enumerated(EnumType.STRING)
    private RoadsideIssueType issueType;

    private String issueLabel;
    private Boolean towRequested;
    private String address;
    private String description;
    private Double latitude;
    private Double longitude;
    private String userEmail;
    private String mechanicName;
    private String mechanicPhone;
    private String mechanicGarage;
    private String mechanicEstimatedArrival;
    private String mechanicDistance;
    private Double mechanicRating;
    private String mechanicSpeciality;
    private Long stationId;
    private String stationName;
    private Long stationMechanicId;

    @CreationTimestamp
    private Instant createdAt;

    private Instant resolvedAt;
    private Instant cancelledAt;
}
