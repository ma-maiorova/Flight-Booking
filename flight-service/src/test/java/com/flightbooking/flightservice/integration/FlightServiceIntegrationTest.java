package com.flightbooking.flightservice.integration;

import com.flightbooking.flightservice.domain.*;
import com.flightbooking.flightservice.repository.FlightRepository;
import com.flightbooking.flightservice.repository.SeatReservationRepository;
import com.flightbooking.flightservice.service.CacheService;
import com.flightbooking.flightservice.service.FlightService;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Integration tests for FlightService — real PostgreSQL via Testcontainers,
 * Redis replaced with a @MockBean so no Sentinel is needed.
 *
 * Tests cover:
 *  - searchFlights uses the real DB (seed data via Liquibase)
 *  - getFlightById hit/miss against real DB
 *  - reserveSeats + releaseReservation atomically update available_seats in DB
 *  - Cross-service gRPC flow verified by BookingIntegrationTest (booking-service module)
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlightServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("flight_db")
            .withUsername("flight_user")
            .withPassword("flight_pass");

    @MockBean
    CacheService cacheService;

    @Autowired
    FlightService flightService;

    @Autowired
    FlightRepository flightRepository;

    @Autowired
    SeatReservationRepository seatReservationRepository;

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void mockCache() {
        when(cacheService.getFlight(any())).thenReturn(Optional.empty());
        when(cacheService.getSearch(any(), any(), any())).thenReturn(Optional.empty());
    }

    // ─── searchFlights ─────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void searchFlights_seedData_svoToLed_returnsFlights() {
        List<Flight> results = flightService.searchFlights("SVO", "LED", null);
        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(f -> f.getOriginCode().equals("SVO")
                && f.getDestinationCode().equals("LED"));
    }

    @Test
    @Order(2)
    void searchFlights_withDate_filtersByDate() {
        List<Flight> results = flightService.searchFlights("SVO", "LED", "2026-04-01");
        assertThat(results).isNotEmpty();
        results.forEach(f -> assertThat(f.getDepartureDate().toString()).isEqualTo("2026-04-01"));
    }

    @Test
    @Order(3)
    void searchFlights_noMatchingRoute_returnsEmpty() {
        List<Flight> results = flightService.searchFlights("JFK", "LAX", null);
        assertThat(results).isEmpty();
    }

    // ─── getFlightById ──────────────────────────────────────────────────────────

    @Test
    @Order(4)
    void getFlightById_knownSeedFlight_returnsCorrectData() {
        UUID knownId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        Flight flight = flightService.getFlightById(knownId);
        assertThat(flight.getFlightNumber()).isEqualTo("SU1234");
        assertThat(flight.getOriginCode()).isEqualTo("SVO");
        assertThat(flight.getTotalSeats()).isEqualTo(180);
    }

    @Test
    @Order(5)
    void getFlightById_unknownId_throwsNotFound() {
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> flightService.getFlightById(UUID.randomUUID()));
        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
    }

    // ─── reserveSeats + releaseReservation ──────────────────────────────────────

    @Test
    @Order(6)
    void reserveSeats_decreasesAvailableSeatsInDb() {
        UUID flightId = UUID.fromString("10000000-0000-0000-0000-000000000002");
        UUID bookingId = UUID.randomUUID();

        int before = flightRepository.findById(flightId).orElseThrow().getAvailableSeats();
        flightService.reserveSeats(flightId, bookingId, 2);
        int after = flightRepository.findById(flightId).orElseThrow().getAvailableSeats();

        assertThat(after).isEqualTo(before - 2);
    }

    @Test
    @Order(7)
    void reserveSeats_idempotent_doesNotDoubleDecrement() {
        UUID flightId = UUID.fromString("10000000-0000-0000-0000-000000000003");
        UUID bookingId = UUID.randomUUID();

        flightService.reserveSeats(flightId, bookingId, 1);
        int afterFirst = flightRepository.findById(flightId).orElseThrow().getAvailableSeats();

        flightService.reserveSeats(flightId, bookingId, 1);
        int afterSecond = flightRepository.findById(flightId).orElseThrow().getAvailableSeats();

        assertThat(afterFirst).isEqualTo(afterSecond);
    }

    @Test
    @Order(8)
    void reserveAndRelease_restoresAvailableSeats() {
        UUID flightId = UUID.fromString("10000000-0000-0000-0000-000000000005");
        UUID bookingId = UUID.randomUUID();

        int initial = flightRepository.findById(flightId).orElseThrow().getAvailableSeats();
        flightService.reserveSeats(flightId, bookingId, 3);
        flightService.releaseReservation(bookingId);
        int restored = flightRepository.findById(flightId).orElseThrow().getAvailableSeats();

        assertThat(restored).isEqualTo(initial);
    }

    @Test
    @Order(9)
    void reserveSeats_notEnoughSeats_throwsResourceExhausted() {
        UUID flightId = UUID.fromString("10000000-0000-0000-0000-000000000013");
        // Seed data: DP503 — DME→AER, only 20 seats available
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> flightService.reserveSeats(flightId, UUID.randomUUID(), 999));
        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
    }
}
