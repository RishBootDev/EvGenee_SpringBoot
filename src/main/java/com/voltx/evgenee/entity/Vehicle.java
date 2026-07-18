package com.voltx.evgenee.entity;

import com.voltx.evgenee.enums.ConnectorType;
import com.voltx.evgenee.enums.VehicleType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "vehicles")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Vehicle {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String model;
    private String licensePlate;
    @Enumerated(EnumType.STRING)
    private ConnectorType connectorType;

    @Enumerated(EnumType.STRING)
    private VehicleType type;

    private Double batteryCapacity;

    @ManyToOne
    @JoinColumn(name = "owner_id")
    private EvUser owner;
}
