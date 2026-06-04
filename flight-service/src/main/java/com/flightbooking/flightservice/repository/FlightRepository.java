package com.flightbooking.flightservice.repository;

import com.flightbooking.flightservice.domain.Flight;
import com.flightbooking.flightservice.domain.FlightStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FlightRepository extends JpaRepository<Flight, UUID> {

    // Used for seat reservation - acquires row-level lock to prevent race conditions
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Flight f WHERE f.id = :id")
    Optional<Flight> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            SELECT f FROM Flight f
            WHERE f.originCode = :origin
              AND f.destinationCode = :destination
              AND f.status = :status
            """)
    List<Flight> findByRouteAndStatus(
            @Param("origin") String origin,
            @Param("destination") String destination,
            @Param("status") FlightStatus status
    );

    @Query("""
            SELECT f FROM Flight f
            WHERE f.originCode = :origin
              AND f.destinationCode = :destination
              AND f.departureDate = :date
              AND f.status = :status
            """)
    List<Flight> findByRouteAndDateAndStatus(
            @Param("origin") String origin,
            @Param("destination") String destination,
            @Param("date") LocalDate date,
            @Param("status") FlightStatus status
    );
}
