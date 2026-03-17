package com.flightbooking.bookingservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;

@Data
public class FlightResponse {

    @JsonProperty("id")
    private String id;

    @JsonProperty("flightNumber")
    private String flightNumber;

    @JsonProperty("airlineCode")
    private String airlineCode;

    @JsonProperty("airlineName")
    private String airlineName;

    @JsonProperty("origin")
    private String origin;

    @JsonProperty("destination")
    private String destination;

    @JsonProperty("departureTime")
    private Instant departureTime;

    @JsonProperty("arrivalTime")
    private Instant arrivalTime;

    @JsonProperty("totalSeats")
    private int totalSeats;

    @JsonProperty("availableSeats")
    private int availableSeats;

    @JsonProperty("price")
    private double price;

    @JsonProperty("status")
    private String status;
}
