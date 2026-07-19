package com.voltx.evgenee.repository;

import com.voltx.evgenee.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    Optional<Review> findByStationIdAndUserId(Long stationId, Long userId);
}
