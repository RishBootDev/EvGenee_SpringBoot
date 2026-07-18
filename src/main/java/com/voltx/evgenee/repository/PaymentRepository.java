package com.voltx.evgenee.repository;

import com.voltx.evgenee.entity.Payment;
import com.voltx.evgenee.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByOrderId(String orderId);

    List<Payment> findByBookingIdAndStatus(Long bookingId, PaymentStatus status);
}
