package com.flightbooking.bookingservice.client;

import com.flightbooking.grpc.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Client for Flight Service gRPC API.
 *
 * - All methods are intercepted by CircuitBreakerAspect (Spring AOP).
 * - All gRPC calls use RetryExecutor for retry with exponential backoff.
 * - API key is automatically injected via GrpcClientConfig global interceptor.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlightServiceClient {

    @GrpcClient("flight-service")
    private FlightServiceGrpc.FlightServiceBlockingStub flightServiceStub;

    private final RetryExecutor retryExecutor;

    /**
     * Search scheduled flights by route and optional date.
     */
    public List<FlightInfo> searchFlights(String origin, String destination, String date) {
        log.debug("searchFlights: origin={} destination={} date={}", origin, destination, date);
        SearchFlightsResponse response = retryExecutor.execute("searchFlights", () ->
            flightServiceStub.searchFlights(
                SearchFlightsRequest.newBuilder()
                    .setOrigin(origin)
                    .setDestination(destination)
                    .setDate(date != null ? date : "")
                    .build()
            )
        );
        return response.getFlightsList();
    }

    /**
     * Get a single flight by UUID.
     */
    public FlightInfo getFlight(String flightId) {
        log.debug("getFlight: flightId={}", flightId);
        GetFlightResponse response = retryExecutor.execute("getFlight", () ->
            flightServiceStub.getFlight(
                GetFlightRequest.newBuilder()
                    .setFlightId(flightId)
                    .build()
            )
        );
        return response.getFlight();
    }

    /**
     * Reserve seats for a booking. Idempotent by bookingId.
     */
    public ReserveSeatsResponse reserveSeats(String flightId, String bookingId, int seatCount) {
        log.debug("reserveSeats: flightId={} bookingId={} seatCount={}", flightId, bookingId, seatCount);
        return retryExecutor.execute("reserveSeats", () ->
            flightServiceStub.reserveSeats(
                ReserveSeatsRequest.newBuilder()
                    .setFlightId(flightId)
                    .setBookingId(bookingId)
                    .setSeatCount(seatCount)
                    .build()
            )
        );
    }

    /**
     * Release a seat reservation by booking ID.
     */
    public ReleaseReservationResponse releaseReservation(String bookingId) {
        log.debug("releaseReservation: bookingId={}", bookingId);
        return retryExecutor.execute("releaseReservation", () ->
            flightServiceStub.releaseReservation(
                ReleaseReservationRequest.newBuilder()
                    .setBookingId(bookingId)
                    .build()
            )
        );
    }
}
