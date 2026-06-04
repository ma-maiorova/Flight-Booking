package com.flightbooking.bookingservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.UUID;

@Data
public class BookingRequest {

    @JsonProperty("userId")
    private UUID userId;

    @JsonProperty("flightId")
    private UUID flightId;

    @JsonProperty("passengerName")
    private String passengerName;

    @JsonProperty("passengerEmail")
    private String passengerEmail;

    @JsonProperty("seatCount")
    private int seatCount;
}
