package com.flightbooking.bookingservice.service;

import com.flightbooking.bookingservice.client.FlightServiceClient;
import com.flightbooking.bookingservice.domain.Booking;
import com.flightbooking.bookingservice.domain.BookingStatus;
import com.flightbooking.bookingservice.dto.BookingRequest;
import com.flightbooking.bookingservice.dto.BookingResponse;
import com.flightbooking.bookingservice.repository.BookingRepository;
import com.flightbooking.grpc.FlightInfo;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private final BookingRepository bookingRepository;
    private final FlightServiceClient flightServiceClient;

    /**
     * Creates a booking following the required flow:
     * 1. GetFlight — validate flight exists and get price
     * 2. ReserveSeats — atomically reserve seats
     * 3. Snapshot price = seatCount * flight.price
     * 4. Persist booking with CONFIRMED status
     *
     * If ReserveSeats fails, no booking is created.
     */
    @Transactional
    public BookingResponse createBooking(BookingRequest request) {
        validateRequest(request);

        // Step 1: Get flight info (validates existence and gets price)
        FlightInfo flight;
        try {
            flight = flightServiceClient.getFlight(request.getFlightId().toString());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Flight not found: " + request.getFlightId());
            }
            throw e;
        }

        log.info("Creating booking: flightId={} userId={} seatCount={}",
            request.getFlightId(), request.getUserId(), request.getSeatCount());

        // Step 2: Reserve seats — generate bookingId upfront for idempotency
        UUID bookingId = UUID.randomUUID();
        try {
            flightServiceClient.reserveSeats(
                request.getFlightId().toString(),
                bookingId.toString(),
                request.getSeatCount()
            );
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.RESOURCE_EXHAUSTED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Not enough seats available: " + e.getStatus().getDescription());
            }
            if (e.getStatus().getCode() == Status.Code.FAILED_PRECONDITION) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Flight is not available for booking: " + e.getStatus().getDescription());
            }
            throw e;
        }

        // Step 3: Snapshot price
        BigDecimal totalPrice = BigDecimal.valueOf(flight.getPrice())
            .multiply(BigDecimal.valueOf(request.getSeatCount()));

        // Step 4: Persist booking
        OffsetDateTime now = OffsetDateTime.now();
        Booking booking = new Booking();
        booking.setId(bookingId);
        booking.setUserId(request.getUserId());
        booking.setFlightId(request.getFlightId());
        booking.setPassengerName(request.getPassengerName());
        booking.setPassengerEmail(request.getPassengerEmail());
        booking.setSeatCount(request.getSeatCount());
        booking.setTotalPrice(totalPrice);
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setCreatedAt(now);
        booking.setUpdatedAt(now);

        Booking saved = bookingRepository.save(booking);
        log.info("Booking created: id={} totalPrice={}", saved.getId(), saved.getTotalPrice());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public BookingResponse getBooking(UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Booking not found: " + bookingId));
        return toResponse(booking);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> getBookingsByUser(UUID userId) {
        return bookingRepository.findByUserId(userId)
            .stream()
            .map(this::toResponse)
            .collect(Collectors.toList());
    }

    /**
     * Cancel a confirmed booking:
     * 1. Validate status is CONFIRMED
     * 2. Release reservation in Flight Service
     * 3. Set status to CANCELLED
     */
    @Transactional
    public BookingResponse cancelBooking(UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Booking not found: " + bookingId));

        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Booking is already " + booking.getStatus());
        }

        // Release seats in Flight Service
        try {
            flightServiceClient.releaseReservation(bookingId.toString());
            log.info("Reservation released for bookingId={}", bookingId);
        } catch (StatusRuntimeException e) {
            // If NOT_FOUND, reservation may have been already released — still cancel the booking
            if (e.getStatus().getCode() != Status.Code.NOT_FOUND) {
                throw e;
            }
            log.warn("Reservation not found for bookingId={}, proceeding with cancellation", bookingId);
        }

        booking.setStatus(BookingStatus.CANCELLED);
        Booking saved = bookingRepository.save(booking);
        log.info("Booking cancelled: id={}", saved.getId());
        return toResponse(saved);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void validateRequest(BookingRequest request) {
        if (request.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        }
        if (request.getFlightId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "flightId is required");
        }
        if (request.getPassengerName() == null || request.getPassengerName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "passengerName is required");
        }
        if (request.getPassengerEmail() == null || request.getPassengerEmail().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "passengerEmail is required");
        }
        if (request.getSeatCount() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "seatCount must be > 0");
        }
    }

    private BookingResponse toResponse(Booking booking) {
        BookingResponse response = new BookingResponse();
        response.setId(booking.getId());
        response.setUserId(booking.getUserId());
        response.setFlightId(booking.getFlightId());
        response.setPassengerName(booking.getPassengerName());
        response.setPassengerEmail(booking.getPassengerEmail());
        response.setSeatCount(booking.getSeatCount());
        response.setTotalPrice(booking.getTotalPrice());
        response.setStatus(booking.getStatus().name());
        response.setCreatedAt(booking.getCreatedAt());
        response.setUpdatedAt(booking.getUpdatedAt());
        return response;
    }
}
