package com.flightbooking.bookingservice.controller;

import com.flightbooking.bookingservice.dto.BookingRequest;
import com.flightbooking.bookingservice.dto.BookingResponse;
import com.flightbooking.bookingservice.service.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    /**
     * POST /bookings
     * Create a new booking. Calls Flight Service to reserve seats.
     */
    @PostMapping
    public ResponseEntity<BookingResponse> createBooking(@RequestBody BookingRequest request) {
        BookingResponse response = bookingService.createBooking(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /bookings/{id}
     * Get a booking by its UUID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<BookingResponse> getBooking(@PathVariable UUID id) {
        return ResponseEntity.ok(bookingService.getBooking(id));
    }

    /**
     * GET /bookings?user_id={uuid}
     * List all bookings for a user.
     */
    @GetMapping
    public ResponseEntity<List<BookingResponse>> listBookings(
        @RequestParam(name = "user_id") UUID userId
    ) {
        return ResponseEntity.ok(bookingService.getBookingsByUser(userId));
    }

    /**
     * POST /bookings/{id}/cancel
     * Cancel a confirmed booking.
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<BookingResponse> cancelBooking(@PathVariable UUID id) {
        return ResponseEntity.ok(bookingService.cancelBooking(id));
    }
}
