package com.flightbooking.flightservice.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "flights",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_flight_number_date",
        columnNames = {"flight_number", "departure_date"}
    )
)
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Flight {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "flight_number", nullable = false, length = 10)
    private String flightNumber;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "airline_id", nullable = false)
    private Airline airline;

    @Column(name = "origin_code", nullable = false, length = 3)
    private String originCode;

    @Column(name = "destination_code", nullable = false, length = 3)
    private String destinationCode;

    @Column(name = "departure_time", nullable = false)
    private OffsetDateTime departureTime;

    @Column(name = "arrival_time", nullable = false)
    private OffsetDateTime arrivalTime;

    @Column(name = "departure_date", nullable = false)
    private LocalDate departureDate;

    @Column(name = "total_seats", nullable = false)
    private int totalSeats;

    @Column(name = "available_seats", nullable = false)
    private int availableSeats;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FlightStatus status = FlightStatus.SCHEDULED;
}
