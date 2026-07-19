package com.voltx.evgenee.entity;

import com.voltx.evgenee.enums.ConnectorType;
import com.voltx.evgenee.enums.CurrencyCode;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Table(name = "station_pricing")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StationPricing {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Double priceperKWh;

    @Enumerated(EnumType.STRING)
    private ConnectorType connectorType;

    private Integer portCount;

    @Enumerated(EnumType.STRING)
    private CurrencyCode currency;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "station_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Station station;
}
