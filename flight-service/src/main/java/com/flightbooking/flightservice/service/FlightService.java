package com.flightbooking.flightservice.service;

import com.flightbooking.flightservice.domain.Flight;
import com.flightbooking.flightservice.domain.FlightStatus;
import com.flightbooking.flightservice.domain.ReservationStatus;
import com.flightbooking.flightservice.domain.SeatReservation;
import com.flightbooking.flightservice.repository.FlightRepository;
import com.flightbooking.flightservice.repository.SeatReservationRepository;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FlightService {

    private final FlightRepository flightRepository;
    private final SeatReservationRepository seatReservationRepository;
    private final CacheService cacheService;

    @Transactional(readOnly = true)
    public List<Flight> searchFlights(String origin, String destination, String date) {
        if (origin == null || origin.isBlank() || destination == null || destination.isBlank()) {
            throw new StatusRuntimeException(
                Status.INVALID_ARGUMENT.withDescription("origin and destination are required"));
        }

        List<Flight> cached = cacheService.getSearch(origin, destination, date).orElse(null);
        if (cached != null) {
            return cached;
        }

        List<Flight> flights;
        if (date != null && !date.isBlank()) {
            LocalDate localDate = LocalDate.parse(date);
            flights = flightRepository.findByRouteAndDateAndStatus(
                origin, destination, localDate, FlightStatus.SCHEDULED);
        } else {
            flights = flightRepository.findByRouteAndStatus(
                origin, destination, FlightStatus.SCHEDULED);
        }

        cacheService.putSearch(origin, destination, date, flights);
        return flights;
    }

    @Transactional(readOnly = true)
    public Flight getFlightById(UUID flightId) {
        Optional<Flight> cached = cacheService.getFlight(flightId);
        if (cached.isPresent()) {
            return cached.get();
        }

        Flight flight = flightRepository.findById(flightId)
            .orElseThrow(() -> new StatusRuntimeException(
                Status.NOT_FOUND.withDescription("Flight not found: " + flightId)));

        cacheService.putFlight(flight);
        return flight;
    }

    /**
     * Atomically reserves seats for a booking.
     * Idempotent: repeated calls with the same bookingId return the existing reservation.
     * Uses SELECT FOR UPDATE to prevent race conditions on last seat.
     */
    @Transactional
    public SeatReservation reserveSeats(UUID flightId, UUID bookingId, int seatCount) {
        if (seatCount <= 0) {
            throw new StatusRuntimeException(
                Status.INVALID_ARGUMENT.withDescription("seat_count must be > 0"));
        }

        // Idempotency check: return existing reservation if already exists
        Optional<SeatReservation> existing = seatReservationRepository.findByBookingId(bookingId);
        if (existing.isPresent()) {
            log.info("Idempotent reserveSeats: returning existing reservation for bookingId={}", bookingId);
            return existing.get();
        }

        // SELECT FOR UPDATE — prevents double-booking the last seat
        Flight flight = flightRepository.findByIdForUpdate(flightId)
            .orElseThrow(() -> new StatusRuntimeException(
                Status.NOT_FOUND.withDescription("Flight not found: " + flightId)));

        if (flight.getStatus() != FlightStatus.SCHEDULED) {
            throw new StatusRuntimeException(
                Status.FAILED_PRECONDITION.withDescription(
                    "Flight is not available for booking, status: " + flight.getStatus()));
        }

        if (flight.getAvailableSeats() < seatCount) {
            throw new StatusRuntimeException(
                Status.RESOURCE_EXHAUSTED.withDescription(
                    "Not enough seats: requested=" + seatCount +
                    " available=" + flight.getAvailableSeats()));
        }

        // Atomically decrease available seats
        flight.setAvailableSeats(flight.getAvailableSeats() - seatCount);
        flightRepository.save(flight);

        // Create reservation record
        SeatReservation reservation = new SeatReservation();
        reservation.setFlight(flight);
        reservation.setBookingId(bookingId);
        reservation.setSeatCount(seatCount);
        reservation.setStatus(ReservationStatus.ACTIVE);
        SeatReservation saved = seatReservationRepository.save(reservation);

        // Invalidate caches for this flight
        cacheService.evictFlight(flightId);
        cacheService.evictSearchByRoute(flight.getOriginCode(), flight.getDestinationCode());

        log.info("Seats reserved: flightId={} bookingId={} seatCount={} availableNow={}",
            flightId, bookingId, seatCount, flight.getAvailableSeats());
        return saved;
    }

    /**
     * Releases an active seat reservation and returns seats to the flight.
     */
    @Transactional
    public SeatReservation releaseReservation(UUID bookingId) {
        SeatReservation reservation = seatReservationRepository
            .findByBookingIdAndStatus(bookingId, ReservationStatus.ACTIVE)
            .orElseThrow(() -> new StatusRuntimeException(
                Status.NOT_FOUND.withDescription(
                    "No active reservation found for bookingId: " + bookingId)));

        // SELECT FOR UPDATE on the flight
        Flight flight = flightRepository.findByIdForUpdate(reservation.getFlight().getId())
            .orElseThrow(() -> new StatusRuntimeException(
                Status.NOT_FOUND.withDescription("Flight not found")));

        // Return seats
        flight.setAvailableSeats(flight.getAvailableSeats() + reservation.getSeatCount());
        flightRepository.save(flight);

        // Mark reservation as released
        reservation.setStatus(ReservationStatus.RELEASED);
        SeatReservation saved = seatReservationRepository.save(reservation);

        // Invalidate caches
        cacheService.evictFlight(flight.getId());
        cacheService.evictSearchByRoute(flight.getOriginCode(), flight.getDestinationCode());

        log.info("Reservation released: bookingId={} seatCount={} availableNow={}",
            bookingId, reservation.getSeatCount(), flight.getAvailableSeats());
        return saved;
    }
}
