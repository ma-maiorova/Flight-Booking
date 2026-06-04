package com.flightbooking.flightservice.repository;

import com.flightbooking.flightservice.domain.ReservationStatus;
import com.flightbooking.flightservice.domain.SeatReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SeatReservationRepository extends JpaRepository<SeatReservation, UUID> {

    Optional<SeatReservation> findByBookingId(UUID bookingId);

    Optional<SeatReservation> findByBookingIdAndStatus(UUID bookingId, ReservationStatus status);
}
