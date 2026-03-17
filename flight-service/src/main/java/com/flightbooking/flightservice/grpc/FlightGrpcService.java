package com.flightbooking.flightservice.grpc;

import com.flightbooking.flightservice.domain.Flight;
import com.flightbooking.flightservice.domain.SeatReservation;
import com.flightbooking.flightservice.interceptor.AuthServerInterceptor;
import com.flightbooking.flightservice.service.FlightService;
import com.flightbooking.grpc.*;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * gRPC server implementation of FlightService.
 * All methods are protected by AuthServerInterceptor.
 */
@Slf4j
@GrpcService(interceptors = {AuthServerInterceptor.class})
@RequiredArgsConstructor
public class FlightGrpcService extends FlightServiceGrpc.FlightServiceImplBase {

    private final FlightService flightService;

    @Override
    public void searchFlights(
        SearchFlightsRequest request,
        StreamObserver<SearchFlightsResponse> responseObserver
    ) {
        log.info("SearchFlights: origin={} destination={} date={}",
            request.getOrigin(), request.getDestination(), request.getDate());
        try {
            List<Flight> flights = flightService.searchFlights(
                request.getOrigin(),
                request.getDestination(),
                request.getDate().isBlank() ? null : request.getDate()
            );

            SearchFlightsResponse response = SearchFlightsResponse.newBuilder()
                .addAllFlights(flights.stream().map(this::toFlightInfo).collect(Collectors.toList()))
                .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (StatusRuntimeException e) {
            log.error("SearchFlights error: {}", e.getStatus());
            responseObserver.onError(e);
        } catch (Exception e) {
            log.error("SearchFlights unexpected error", e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage())
                .withCause(e).asRuntimeException());
        }
    }

    @Override
    public void getFlight(
        GetFlightRequest request,
        StreamObserver<GetFlightResponse> responseObserver
    ) {
        log.info("GetFlight: flightId={}", request.getFlightId());
        try {
            UUID flightId = parseUUID(request.getFlightId(), "flight_id");
            Flight flight = flightService.getFlightById(flightId);

            GetFlightResponse response = GetFlightResponse.newBuilder()
                .setFlight(toFlightInfo(flight))
                .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (StatusRuntimeException e) {
            log.error("GetFlight error: {}", e.getStatus());
            responseObserver.onError(e);
        } catch (Exception e) {
            log.error("GetFlight unexpected error", e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage())
                .withCause(e).asRuntimeException());
        }
    }

    @Override
    public void reserveSeats(
        ReserveSeatsRequest request,
        StreamObserver<ReserveSeatsResponse> responseObserver
    ) {
        log.info("ReserveSeats: flightId={} bookingId={} seatCount={}",
            request.getFlightId(), request.getBookingId(), request.getSeatCount());
        try {
            UUID flightId = parseUUID(request.getFlightId(), "flight_id");
            UUID bookingId = parseUUID(request.getBookingId(), "booking_id");

            SeatReservation reservation = flightService.reserveSeats(
                flightId, bookingId, request.getSeatCount());

            ReserveSeatsResponse response = ReserveSeatsResponse.newBuilder()
                .setReservationId(reservation.getId().toString())
                .setBookingId(reservation.getBookingId().toString())
                .setFlightId(reservation.getFlight().getId().toString())
                .setReservedSeats(reservation.getSeatCount())
                .setStatus(toReservationStatus(reservation.getStatus()))
                .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (StatusRuntimeException e) {
            log.error("ReserveSeats error: {}", e.getStatus());
            responseObserver.onError(e);
        } catch (Exception e) {
            log.error("ReserveSeats unexpected error", e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage())
                .withCause(e).asRuntimeException());
        }
    }

    @Override
    public void releaseReservation(
        ReleaseReservationRequest request,
        StreamObserver<ReleaseReservationResponse> responseObserver
    ) {
        log.info("ReleaseReservation: bookingId={}", request.getBookingId());
        try {
            UUID bookingId = parseUUID(request.getBookingId(), "booking_id");
            SeatReservation reservation = flightService.releaseReservation(bookingId);

            ReleaseReservationResponse response = ReleaseReservationResponse.newBuilder()
                .setReservationId(reservation.getId().toString())
                .setBookingId(reservation.getBookingId().toString())
                .setReleasedSeats(reservation.getSeatCount())
                .setStatus(toReservationStatus(reservation.getStatus()))
                .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (StatusRuntimeException e) {
            log.error("ReleaseReservation error: {}", e.getStatus());
            responseObserver.onError(e);
        } catch (Exception e) {
            log.error("ReleaseReservation unexpected error", e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage())
                .withCause(e).asRuntimeException());
        }
    }

    // ─── Converters ───────────────────────────────────────────────────────────

    private FlightInfo toFlightInfo(Flight flight) {
        return FlightInfo.newBuilder()
            .setId(flight.getId().toString())
            .setFlightNumber(flight.getFlightNumber())
            .setAirlineCode(flight.getAirline().getCode())
            .setAirlineName(flight.getAirline().getName())
            .setOrigin(flight.getOriginCode())
            .setDestination(flight.getDestinationCode())
            .setDepartureTime(toTimestamp(flight.getDepartureTime()))
            .setArrivalTime(toTimestamp(flight.getArrivalTime()))
            .setTotalSeats(flight.getTotalSeats())
            .setAvailableSeats(flight.getAvailableSeats())
            .setPrice(flight.getPrice().doubleValue())
            .setStatus(toFlightStatus(flight.getStatus()))
            .build();
    }

    private Timestamp toTimestamp(OffsetDateTime odt) {
        return Timestamp.newBuilder()
            .setSeconds(odt.toEpochSecond())
            .setNanos(odt.getNano())
            .build();
    }

    private FlightStatus toFlightStatus(com.flightbooking.flightservice.domain.FlightStatus status) {
        return switch (status) {
            case SCHEDULED -> FlightStatus.SCHEDULED;
            case DEPARTED  -> FlightStatus.DEPARTED;
            case CANCELLED -> FlightStatus.CANCELLED;
            case COMPLETED -> FlightStatus.COMPLETED;
        };
    }

    private ReservationStatus toReservationStatus(
        com.flightbooking.flightservice.domain.ReservationStatus status
    ) {
        return switch (status) {
            case ACTIVE   -> ReservationStatus.ACTIVE;
            case RELEASED -> ReservationStatus.RELEASED;
            case EXPIRED  -> ReservationStatus.EXPIRED;
        };
    }

    private UUID parseUUID(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new StatusRuntimeException(
                Status.INVALID_ARGUMENT.withDescription(fieldName + " is required"));
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new StatusRuntimeException(
                Status.INVALID_ARGUMENT.withDescription("Invalid UUID for " + fieldName + ": " + value));
        }
    }
}
