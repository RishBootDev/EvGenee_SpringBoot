package com.voltx.evgenee.entity;

import com.voltx.evgenee.enums.StationApprovalStatus;
import com.voltx.evgenee.enums.StationStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
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

    @Lob
    @Column(columnDefinition = "TEXT")
    private String addressJson;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String amenitiesJson;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String connectorsJson;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String pricingJson;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String imagesJson;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String peakPricingJson;

    @ManyToOne
    @JoinColumn(name = "owner_id")
    private StationOwner owner;

    @OneToMany(mappedBy = "station", cascade = CascadeType.ALL)
    private List<Review> reviews;

    @OneToMany(mappedBy = "station", cascade = CascadeType.ALL)
    private List<Booking> bookings;

    @OneToMany(mappedBy = "station", cascade = CascadeType.ALL)
    private List<StationMechanic> mechanics;
}
