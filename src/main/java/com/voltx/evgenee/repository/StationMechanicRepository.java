package com.voltx.evgenee.repository;

import com.voltx.evgenee.entity.StationMechanic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface StationMechanicRepository extends JpaRepository<StationMechanic, Long> {
    List<StationMechanic> findByStationIdOrderByCreatedAtDesc(Long stationId);

    @Query("SELECT m FROM StationMechanic m WHERE m.station.owner.authUser.email = :ownerEmail ORDER BY m.createdAt DESC")
    List<StationMechanic> findByOwnerEmail(@Param("ownerEmail") String ownerEmail);

    @Query("SELECT m FROM StationMechanic m WHERE m.active = true AND m.station.status = 'active' AND m.station.open = true")
    List<StationMechanic> findAvailableActiveMechanics();
}
