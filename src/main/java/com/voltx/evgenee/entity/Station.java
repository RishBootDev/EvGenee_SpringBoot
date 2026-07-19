package com.voltx.evgenee.entity;

import com.voltx.evgenee.enums.StationApprovalStatus;
import com.voltx.evgenee.enums.StationStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "stations")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Station {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String address;
    private Double latitude;
    private Double longitude;
    private Integer chargersCount;
    private Integer availablePorts;
    private Integer chargingSpeed;
    private Double platformFee;
    @Column(name = "is_open")
    private Boolean open;
    private String openingHours;
    @Enumerated(EnumType.STRING)
    private StationStatus status;
    private String operator;
    private String contactPhone;
    private String contactEmail;
    private String ownerName;
    @Enumerated(EnumType.STRING)
    private StationApprovalStatus approvalStatus;
    private Instant approvedAt;
    private String approvalNote;

    @OneToOne(mappedBy = "station", cascade = CascadeType.ALL, orphanRemoval = true)
    private StationAddress addressDetails;

    @OneToMany(mappedBy = "station", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<StationAmenity> amenities = new ArrayList<>();

    @OneToMany(mappedBy = "station", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<StationConnector> connectors = new ArrayList<>();

    @OneToMany(mappedBy = "station", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<StationPricing> pricing = new ArrayList<>();

    @OneToMany(mappedBy = "station", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<StationImage> images = new ArrayList<>();

    @OneToMany(mappedBy = "station", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<StationPeakPricing> peakPricing = new ArrayList<>();

    @ManyToOne
    @JoinColumn(name = "owner_id")
    private StationOwner owner;

    @OneToMany(mappedBy = "station", cascade = CascadeType.ALL)
    @Builder.Default
    private List<Review> reviews = new ArrayList<>();

    @OneToMany(mappedBy = "station", cascade = CascadeType.ALL)
    @Builder.Default
    private List<Booking> bookings = new ArrayList<>();

    @OneToMany(mappedBy = "station", cascade = CascadeType.ALL)
    @Builder.Default
    private List<StationMechanic> mechanics = new ArrayList<>();
}
