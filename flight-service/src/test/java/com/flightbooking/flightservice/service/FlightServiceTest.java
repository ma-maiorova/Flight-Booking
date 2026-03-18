package com.flightbooking.flightservice.service;

import com.flightbooking.flightservice.domain.*;
import com.flightbooking.flightservice.repository.FlightRepository;
import com.flightbooking.flightservice.repository.SeatReservationRepository;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlightServiceTest {

    @Mock FlightRepository flightRepository;
    @Mock SeatReservationRepository seatReservationRepository;
    @Mock CacheService cacheService;
    @InjectMocks FlightService flightService;

    private UUID flightId;
    private UUID bookingId;
    private Flight flight;
    private Airline airline;

    @BeforeEach
    void setUp() {
        flightId = UUID.randomUUID();
        bookingId = UUID.randomUUID();

        airline = new Airline();
        airline.setCode("SU");
        airline.setName("Aeroflot");

        flight = new Flight();
        flight.setId(flightId);
        flight.setFlightNumber("SU101");
        flight.setAirline(airline);
        flight.setOriginCode("SVO");
        flight.setDestinationCode("LED");
        flight.setDepartureTime(OffsetDateTime.now().plusDays(1));
        flight.setArrivalTime(OffsetDateTime.now().plusDays(1).plusHours(2));
        flight.setDepartureDate(LocalDate.now().plusDays(1));
        flight.setTotalSeats(150);
        flight.setAvailableSeats(100);
        flight.setPrice(new BigDecimal("5000.00"));
        flight.setStatus(FlightStatus.SCHEDULED);
    }

    // ─── searchFlights ─────────────────────────────────────────────────────────

    @Test
    void searchFlights_cacheHit_returnsCachedResult() {
        List<Flight> cached = List.of(flight);
        when(cacheService.getSearch("SVO", "LED", "2026-04-01")).thenReturn(Optional.of(cached));

        List<Flight> result = flightService.searchFlights("SVO", "LED", "2026-04-01");

        assertThat(result).isEqualTo(cached);
        verifyNoInteractions(flightRepository);
    }

    @Test
    void searchFlights_cacheMiss_withDate_queriesDb() {
        when(cacheService.getSearch("SVO", "LED", "2026-04-01")).thenReturn(Optional.empty());
        when(flightRepository.findByRouteAndDateAndStatus(
                eq("SVO"), eq("LED"), eq(LocalDate.parse("2026-04-01")), eq(FlightStatus.SCHEDULED)))
                .thenReturn(List.of(flight));

        List<Flight> result = flightService.searchFlights("SVO", "LED", "2026-04-01");

        assertThat(result).hasSize(1);
        verify(cacheService).putSearch(eq("SVO"), eq("LED"), eq("2026-04-01"), eq(List.of(flight)));
    }

    @Test
    void searchFlights_cacheMiss_withoutDate_queriesDb() {
        when(cacheService.getSearch("SVO", "LED", null)).thenReturn(Optional.empty());
        when(flightRepository.findByRouteAndStatus("SVO", "LED", FlightStatus.SCHEDULED))
                .thenReturn(List.of(flight));

        List<Flight> result = flightService.searchFlights("SVO", "LED", null);

        assertThat(result).hasSize(1);
        verify(cacheService).putSearch(eq("SVO"), eq("LED"), isNull(), eq(List.of(flight)));
    }

    @Test
    void searchFlights_nullOrigin_throwsInvalidArgument() {
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> flightService.searchFlights(null, "LED", null));
        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void searchFlights_blankDestination_throwsInvalidArgument() {
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> flightService.searchFlights("SVO", "  ", null));
        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    // ─── getFlightById ──────────────────────────────────────────────────────────

    @Test
    void getFlightById_cacheHit_returnsCached() {
        when(cacheService.getFlight(flightId)).thenReturn(Optional.of(flight));

        Flight result = flightService.getFlightById(flightId);

        assertThat(result).isEqualTo(flight);
        verifyNoInteractions(flightRepository);
    }

    @Test
    void getFlightById_cacheMiss_found_storesInCache() {
        when(cacheService.getFlight(flightId)).thenReturn(Optional.empty());
        when(flightRepository.findById(flightId)).thenReturn(Optional.of(flight));

        Flight result = flightService.getFlightById(flightId);

        assertThat(result).isEqualTo(flight);
        verify(cacheService).putFlight(flight);
    }

    @Test
    void getFlightById_notFound_throwsNotFound() {
        when(cacheService.getFlight(flightId)).thenReturn(Optional.empty());
        when(flightRepository.findById(flightId)).thenReturn(Optional.empty());

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> flightService.getFlightById(flightId));
        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
    }

    // ─── reserveSeats ──────────────────────────────────────────────────────────

    @Test
    void reserveSeats_success_decreasesAvailableSeats() {
        flight.setAvailableSeats(10);
        when(seatReservationRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());
        when(flightRepository.findByIdForUpdate(flightId)).thenReturn(Optional.of(flight));
        when(flightRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(seatReservationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SeatReservation result = flightService.reserveSeats(flightId, bookingId, 3);

        ArgumentCaptor<Flight> flightCaptor = ArgumentCaptor.forClass(Flight.class);
        verify(flightRepository).save(flightCaptor.capture());
        assertThat(flightCaptor.getValue().getAvailableSeats()).isEqualTo(7);

        ArgumentCaptor<SeatReservation> resCaptor = ArgumentCaptor.forClass(SeatReservation.class);
        verify(seatReservationRepository).save(resCaptor.capture());
        assertThat(resCaptor.getValue().getSeatCount()).isEqualTo(3);
        assertThat(resCaptor.getValue().getStatus()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(resCaptor.getValue().getBookingId()).isEqualTo(bookingId);

        verify(cacheService).evictFlight(flightId);
        verify(cacheService).evictSearchByRoute("SVO", "LED");
    }

    @Test
    void reserveSeats_idempotent_returnsExistingReservation() {
        SeatReservation existing = new SeatReservation();
        existing.setBookingId(bookingId);
        existing.setSeatCount(2);
        existing.setStatus(ReservationStatus.ACTIVE);
        when(seatReservationRepository.findByBookingId(bookingId)).thenReturn(Optional.of(existing));

        SeatReservation result = flightService.reserveSeats(flightId, bookingId, 2);

        assertThat(result).isEqualTo(existing);
        verifyNoInteractions(flightRepository);
    }

    @Test
    void reserveSeats_notEnoughSeats_throwsResourceExhausted() {
        flight.setAvailableSeats(1);
        when(seatReservationRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());
        when(flightRepository.findByIdForUpdate(flightId)).thenReturn(Optional.of(flight));

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> flightService.reserveSeats(flightId, bookingId, 5));
        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
    }

    @Test
    void reserveSeats_flightNotFound_throwsNotFound() {
        when(seatReservationRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());
        when(flightRepository.findByIdForUpdate(flightId)).thenReturn(Optional.empty());

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> flightService.reserveSeats(flightId, bookingId, 2));
        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    void reserveSeats_flightNotScheduled_throwsFailedPrecondition() {
        flight.setStatus(FlightStatus.CANCELLED);
        when(seatReservationRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());
        when(flightRepository.findByIdForUpdate(flightId)).thenReturn(Optional.of(flight));

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> flightService.reserveSeats(flightId, bookingId, 2));
        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    }

    @Test
    void reserveSeats_invalidSeatCount_throwsInvalidArgument() {
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> flightService.reserveSeats(flightId, bookingId, 0));
        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    // ─── releaseReservation ────────────────────────────────────────────────────

    @Test
    void releaseReservation_success_returnsSeats() {
        SeatReservation reservation = new SeatReservation();
        reservation.setBookingId(bookingId);
        reservation.setSeatCount(3);
        reservation.setStatus(ReservationStatus.ACTIVE);
        reservation.setFlight(flight);
        flight.setAvailableSeats(7);

        when(seatReservationRepository.findByBookingIdAndStatus(bookingId, ReservationStatus.ACTIVE))
                .thenReturn(Optional.of(reservation));
        when(flightRepository.findByIdForUpdate(flightId)).thenReturn(Optional.of(flight));
        when(flightRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(seatReservationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SeatReservation result = flightService.releaseReservation(bookingId);

        ArgumentCaptor<Flight> flightCaptor = ArgumentCaptor.forClass(Flight.class);
        verify(flightRepository).save(flightCaptor.capture());
        assertThat(flightCaptor.getValue().getAvailableSeats()).isEqualTo(10);

        ArgumentCaptor<SeatReservation> resCaptor = ArgumentCaptor.forClass(SeatReservation.class);
        verify(seatReservationRepository).save(resCaptor.capture());
        assertThat(resCaptor.getValue().getStatus()).isEqualTo(ReservationStatus.RELEASED);

        verify(cacheService).evictFlight(flightId);
        verify(cacheService).evictSearchByRoute("SVO", "LED");
    }

    @Test
    void releaseReservation_noActiveReservation_throwsNotFound() {
        when(seatReservationRepository.findByBookingIdAndStatus(bookingId, ReservationStatus.ACTIVE))
                .thenReturn(Optional.empty());

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> flightService.releaseReservation(bookingId));
        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
    }
}
