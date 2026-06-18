package com.voltx.evgenee.repository;

import com.voltx.evgenee.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {

    @Modifying
    @Query("UPDATE Booking b SET b.status = 'NO_SHOW' WHERE b.status = 'CONFIRMED' AND b.endTime < :now")
    int markNoShows(@Param("now") Instant now);

    @Modifying
    @Query("UPDATE Booking b SET b.status = 'COMPLETED', b.completedAt = :now WHERE b.status = 'IN_PROGRESS' AND b.endTime <= :now")
    int autoCompleteBookings(@Param("now") Instant now);

    @Modifying
    @Query("UPDATE Booking b SET b.status = 'CANCELLED', b.cancellationReason = 'Auto-cancelled: Booking expired' WHERE b.status = 'PENDING' AND b.createdAt < :tenMinutesAgo")
    int expirePendingBookings(@Param("tenMinutesAgo") Instant tenMinutesAgo);

    @Query("SELECT b FROM Booking b JOIN FETCH b.user u JOIN FETCH u.authUser WHERE b.status = 'CONFIRMED' AND b.startTime >= :windowStart AND b.startTime < :windowEnd AND b.reminderSent = false")
    List<Booking> findBookingsForReminder(@Param("windowStart") Instant windowStart, @Param("windowEnd") Instant windowEnd);

    @Query("SELECT b FROM Booking b WHERE b.user.authUser.email = :email ORDER BY b.createdAt DESC")
    List<Booking> findByUserEmailOrderByCreatedAtDesc(@Param("email") String email);

    @Query("SELECT b FROM Booking b WHERE b.station.id = :stationId ORDER BY b.startTime DESC")
    List<Booking> findByStationIdOrderByStartTimeDesc(@Param("stationId") Long stationId);

    @Query("SELECT b FROM Booking b WHERE b.station.id = :stationId AND b.startTime < :end AND b.endTime > :start")
    List<Booking> findOverlappingBookings(@Param("stationId") Long stationId, @Param("end") Instant end, @Param("start") Instant start);
}
