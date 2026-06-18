package com.voltx.evgenee.repository;

import com.voltx.evgenee.entity.StationOwner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StationOwnerRepository extends JpaRepository<StationOwner, Long> {
    @Query("SELECT so FROM StationOwner so WHERE so.authUser.email = :email")
    Optional<StationOwner> findByEmail(@Param("email") String email);

    @Query("SELECT so FROM StationOwner so WHERE so.authUser.id = :authUserId")
    Optional<StationOwner> findByAuthUserId(@Param("authUserId") Long authUserId);
}
