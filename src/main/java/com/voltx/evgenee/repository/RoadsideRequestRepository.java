package com.voltx.evgenee.repository;

import com.voltx.evgenee.entity.RoadsideRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoadsideRequestRepository extends JpaRepository<RoadsideRequest, Long> {
    List<RoadsideRequest> findByUserEmailOrderByCreatedAtDesc(String userEmail);
}
