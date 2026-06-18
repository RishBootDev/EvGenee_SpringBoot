package com.voltx.evgenee.repository;

import com.voltx.evgenee.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRepository extends JpaRepository<Review, Long> {
}
