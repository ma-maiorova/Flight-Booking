package com.flightbooking.bookingservice.controller;

import com.flightbooking.bookingservice.client.FlightServiceClient;
import com.flightbooking.bookingservice.dto.FlightResponse;
import com.flightbooking.grpc.FlightInfo;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/flights")
@RequiredArgsConstructor
public class FlightController {

    private final FlightServiceClient flightServiceClient;

    /**
     * GET /flights?origin=SVO&destination=LED&date=2026-04-01
     * Search scheduled flights (proxied to Flight Service).
     */
    @GetMapping
    public ResponseEntity<List<FlightResponse>> searchFlights(
        @RequestParam String origin,
        @RequestParam String destination,
        @RequestParam(required = false) String date
    ) {
        if (origin == null || origin.isBlank() || destination == null || destination.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "origin and destination are required");
        }

        List<FlightInfo> flights = flightServiceClient.searchFlights(origin, destination, date);
        return ResponseEntity.ok(
            flights.stream().map(this::toFlightResponse).collect(Collectors.toList())
        );
    }

    /**
     * GET /flights/{id}
     * Get a single flight by ID (proxied to Flight Service).
     */
    @GetMapping("/{id}")
    public ResponseEntity<FlightResponse> getFlight(@PathVariable String id) {
        try {
            FlightInfo flight = flightServiceClient.getFlight(id);
            return ResponseEntity.ok(toFlightResponse(flight));
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Flight not found: " + id);
            }
            throw e;
        }
    }

    // ─── Converter ────────────────────────────────────────────────────────────

    private FlightResponse toFlightResponse(FlightInfo info) {
        FlightResponse response = new FlightResponse();
        response.setId(info.getId());
        response.setFlightNumber(info.getFlightNumber());
        response.setAirlineCode(info.getAirlineCode());
        response.setAirlineName(info.getAirlineName());
        response.setOrigin(info.getOrigin());
        response.setDestination(info.getDestination());
        response.setDepartureTime(Instant.ofEpochSecond(
            info.getDepartureTime().getSeconds(), info.getDepartureTime().getNanos()));
        response.setArrivalTime(Instant.ofEpochSecond(
            info.getArrivalTime().getSeconds(), info.getArrivalTime().getNanos()));
        response.setTotalSeats(info.getTotalSeats());
        response.setAvailableSeats(info.getAvailableSeats());
        response.setPrice(info.getPrice());
        response.setStatus(info.getStatus().name());
        return response;
    }
}
