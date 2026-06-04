package com.flightbooking.bookingservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.flightbooking.bookingservice.dto.BookingRequest;
import com.flightbooking.bookingservice.dto.BookingResponse;
import com.flightbooking.bookingservice.service.BookingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.flightbooking.bookingservice.config.TestMetricsConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {BookingController.class, GlobalExceptionHandler.class})
@Import(TestMetricsConfig.class)
@ActiveProfiles("test")
class BookingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BookingService bookingService;

    private ObjectMapper objectMapper;
    private UUID userId;
    private UUID flightId;
    private UUID bookingId;
    private BookingResponse sampleResponse;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        userId = UUID.randomUUID();
        flightId = UUID.randomUUID();
        bookingId = UUID.randomUUID();

        sampleResponse = new BookingResponse();
        sampleResponse.setId(bookingId);
        sampleResponse.setUserId(userId);
        sampleResponse.setFlightId(flightId);
        sampleResponse.setPassengerName("John");
        sampleResponse.setPassengerEmail("j@example.com");
        sampleResponse.setSeatCount(2);
        sampleResponse.setTotalPrice(new BigDecimal("10000.00"));
        sampleResponse.setStatus("CONFIRMED");
        sampleResponse.setCreatedAt(OffsetDateTime.now());
        sampleResponse.setUpdatedAt(OffsetDateTime.now());
    }

    private String buildRequestJson(UUID userId, UUID flightId) throws Exception {
        BookingRequest req = new BookingRequest();
        req.setUserId(userId);
        req.setFlightId(flightId);
        req.setPassengerName("John");
        req.setPassengerEmail("j@example.com");
        req.setSeatCount(2);
        return objectMapper.writeValueAsString(req);
    }

    // ─── POST /bookings ───────────────────────────────────────────────────────

    @Test
    void postBookings_returns201WithBody() throws Exception {
        when(bookingService.createBooking(any(BookingRequest.class))).thenReturn(sampleResponse);

        mockMvc.perform(post("/bookings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(buildRequestJson(userId, flightId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(bookingId.toString()))
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.passengerName").value("John"))
            .andExpect(jsonPath("$.seatCount").value(2));
    }

    @Test
    void postBookings_returns409WhenNoSeats() throws Exception {
        when(bookingService.createBooking(any(BookingRequest.class)))
            .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Not enough seats"));

        mockMvc.perform(post("/bookings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(buildRequestJson(userId, flightId)))
            .andExpect(status().isConflict());
    }

    @Test
    void postBookings_returns400WhenBadRequest() throws Exception {
        when(bookingService.createBooking(any(BookingRequest.class)))
            .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required"));

        mockMvc.perform(post("/bookings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(buildRequestJson(userId, flightId)))
            .andExpect(status().isBadRequest());
    }

    // ─── GET /bookings/{id} ───────────────────────────────────────────────────

    @Test
    void getBookingById_returns200() throws Exception {
        when(bookingService.getBooking(bookingId)).thenReturn(sampleResponse);

        mockMvc.perform(get("/bookings/{id}", bookingId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(bookingId.toString()))
            .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void getBookingById_returns404WhenNotFound() throws Exception {
        when(bookingService.getBooking(bookingId))
            .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found"));

        mockMvc.perform(get("/bookings/{id}", bookingId))
            .andExpect(status().isNotFound());
    }

    // ─── GET /bookings?user_id={uuid} ─────────────────────────────────────────

    @Test
    void listBookings_returns200List() throws Exception {
        BookingResponse second = new BookingResponse();
        second.setId(UUID.randomUUID());
        second.setUserId(userId);
        second.setFlightId(flightId);
        second.setPassengerName("Jane");
        second.setPassengerEmail("jane@example.com");
        second.setSeatCount(1);
        second.setTotalPrice(new BigDecimal("5000.00"));
        second.setStatus("CONFIRMED");
        second.setCreatedAt(OffsetDateTime.now());
        second.setUpdatedAt(OffsetDateTime.now());

        when(bookingService.getBookingsByUser(userId)).thenReturn(List.of(sampleResponse, second));

        mockMvc.perform(get("/bookings").param("user_id", userId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));
    }

    // ─── POST /bookings/{id}/cancel ───────────────────────────────────────────

    @Test
    void cancelBooking_returns200() throws Exception {
        BookingResponse cancelled = new BookingResponse();
        cancelled.setId(bookingId);
        cancelled.setUserId(userId);
        cancelled.setFlightId(flightId);
        cancelled.setPassengerName("John");
        cancelled.setPassengerEmail("j@example.com");
        cancelled.setSeatCount(2);
        cancelled.setTotalPrice(new BigDecimal("10000.00"));
        cancelled.setStatus("CANCELLED");
        cancelled.setCreatedAt(OffsetDateTime.now());
        cancelled.setUpdatedAt(OffsetDateTime.now());
        when(bookingService.cancelBooking(bookingId)).thenReturn(cancelled);

        mockMvc.perform(post("/bookings/{id}/cancel", bookingId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancelBooking_returns409WhenAlreadyCancelled() throws Exception {
        when(bookingService.cancelBooking(bookingId))
            .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Booking is already CANCELLED"));

        mockMvc.perform(post("/bookings/{id}/cancel", bookingId))
            .andExpect(status().isConflict());
    }
}
