package com.voltx.evgenee.repository;

import com.voltx.evgenee.entity.Station;
import com.voltx.evgenee.enums.StationApprovalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StationRepository extends JpaRepository<Station, Long> {
    List<Station> findByOwnerId(Long ownerId);

    List<Station> findByStatusOrderByIdDesc(String status);

    List<Station> findByApprovalStatusOrderByIdDesc(StationApprovalStatus approvalStatus);

    List<Station> findByApprovalStatusAndLatitudeIsNotNullAndLongitudeIsNotNullOrderByIdDesc(StationApprovalStatus approvalStatus);

    List<Station> findByApprovalStatusIsNull();

    long countByStatus(String status);
}
