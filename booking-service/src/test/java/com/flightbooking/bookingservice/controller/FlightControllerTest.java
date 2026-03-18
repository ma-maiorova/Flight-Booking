package com.flightbooking.bookingservice.controller;

import com.flightbooking.bookingservice.circuitbreaker.CircuitBreaker;
import com.flightbooking.bookingservice.client.FlightServiceClient;
import com.flightbooking.grpc.FlightInfo;
import com.flightbooking.grpc.FlightStatus;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {FlightController.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class FlightControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FlightServiceClient flightServiceClient;

    @MockBean
    private CircuitBreaker circuitBreaker;

    private FlightInfo sampleFlight;
    private String flightId;

    @BeforeEach
    void setUp() {
        flightId = UUID.randomUUID().toString();
        Instant departure = Instant.parse("2026-04-01T10:00:00Z");
        Instant arrival = Instant.parse("2026-04-01T12:00:00Z");

        sampleFlight = FlightInfo.newBuilder()
            .setId(flightId)
            .setFlightNumber("SU100")
            .setAirlineCode("SU")
            .setAirlineName("Aeroflot")
            .setOrigin("SVO")
            .setDestination("LED")
            .setDepartureTime(Timestamp.newBuilder()
                .setSeconds(departure.getEpochSecond())
                .setNanos(departure.getNano())
                .build())
            .setArrivalTime(Timestamp.newBuilder()
                .setSeconds(arrival.getEpochSecond())
                .setNanos(arrival.getNano())
                .build())
            .setTotalSeats(150)
            .setAvailableSeats(80)
            .setPrice(5000.0)
            .setStatus(FlightStatus.SCHEDULED)
            .build();
    }

    // ─── GET /flights ─────────────────────────────────────────────────────────

    @Test
    void searchFlights_withOriginAndDestination_returns200List() throws Exception {
        when(flightServiceClient.searchFlights(eq("SVO"), eq("LED"), isNull()))
            .thenReturn(List.of(sampleFlight));

        mockMvc.perform(get("/flights")
                .param("origin", "SVO")
                .param("destination", "LED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].flightNumber").value("SU100"))
            .andExpect(jsonPath("$[0].origin").value("SVO"))
            .andExpect(jsonPath("$[0].destination").value("LED"));
    }

    @Test
    void searchFlights_withDate_returns200List() throws Exception {
        when(flightServiceClient.searchFlights(eq("SVO"), eq("LED"), eq("2026-04-01")))
            .thenReturn(List.of(sampleFlight));

        mockMvc.perform(get("/flights")
                .param("origin", "SVO")
                .param("destination", "LED")
                .param("date", "2026-04-01"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(flightId));
    }

    // ─── GET /flights/{id} ────────────────────────────────────────────────────

    @Test
    void getFlight_found_returns200() throws Exception {
        when(flightServiceClient.getFlight(flightId)).thenReturn(sampleFlight);

        mockMvc.perform(get("/flights/{id}", flightId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(flightId))
            .andExpect(jsonPath("$.flightNumber").value("SU100"))
            .andExpect(jsonPath("$.airlineName").value("Aeroflot"))
            .andExpect(jsonPath("$.price").value(5000.0));
    }

    @Test
    void getFlight_notFound_returns404() throws Exception {
        when(flightServiceClient.getFlight(flightId))
            .thenThrow(new StatusRuntimeException(
                Status.NOT_FOUND.withDescription("Flight not found")));

        mockMvc.perform(get("/flights/{id}", flightId))
            .andExpect(status().isNotFound());
    }

    // ─── Validation ───────────────────────────────────────────────────────────

    @Test
    void searchFlights_blankOrigin_returns400() throws Exception {
        // Blank origin reaches the controller's explicit validation → throws ResponseStatusException(400)
        mockMvc.perform(get("/flights")
                .param("origin", "")
                .param("destination", "LED"))
            .andExpect(status().isBadRequest());
    }
}
