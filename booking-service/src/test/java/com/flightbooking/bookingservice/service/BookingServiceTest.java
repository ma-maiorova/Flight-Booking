package com.flightbooking.bookingservice.service;

import com.flightbooking.bookingservice.client.FlightServiceClient;
import com.flightbooking.bookingservice.domain.Booking;
import com.flightbooking.bookingservice.domain.BookingStatus;
import com.flightbooking.bookingservice.dto.BookingRequest;
import com.flightbooking.bookingservice.dto.BookingResponse;
import com.flightbooking.bookingservice.repository.BookingRepository;
import com.flightbooking.grpc.FlightInfo;
import com.flightbooking.grpc.FlightStatus;
import com.flightbooking.grpc.ReserveSeatsResponse;
import com.flightbooking.grpc.ReleaseReservationResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private FlightServiceClient flightServiceClient;

    @InjectMocks
    private BookingService bookingService;

    private UUID userId;
    private UUID flightId;
    private FlightInfo flightInfo;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        flightId = UUID.randomUUID();

        flightInfo = FlightInfo.newBuilder()
            .setId(flightId.toString())
            .setPrice(5000.0)
            .setAvailableSeats(100)
            .setTotalSeats(150)
            .setStatus(FlightStatus.SCHEDULED)
            .build();
    }

    private BookingRequest validRequest() {
        BookingRequest req = new BookingRequest();
        req.setUserId(userId);
        req.setFlightId(flightId);
        req.setPassengerName("John Doe");
        req.setPassengerEmail("john@example.com");
        req.setSeatCount(2);
        return req;
    }

    private Booking savedBooking(UUID id, UUID userId, UUID flightId,
                                  String name, String email, int seats,
                                  BigDecimal totalPrice, BookingStatus status) {
        Booking booking = new Booking();
        booking.setId(id);
        booking.setUserId(userId);
        booking.setFlightId(flightId);
        booking.setPassengerName(name);
        booking.setPassengerEmail(email);
        booking.setSeatCount(seats);
        booking.setTotalPrice(totalPrice);
        booking.setStatus(status);
        booking.setCreatedAt(OffsetDateTime.now());
        booking.setUpdatedAt(OffsetDateTime.now());
        return booking;
    }

    // ─── createBooking ────────────────────────────────────────────────────────

    @Test
    void createBooking_success_returnsConfirmedBookingWithCorrectPrice() {
        BookingRequest request = validRequest();
        when(flightServiceClient.getFlight(flightId.toString())).thenReturn(flightInfo);
        when(flightServiceClient.reserveSeats(anyString(), anyString(), anyInt()))
            .thenReturn(ReserveSeatsResponse.newBuilder().build());
        when(bookingRepository.save(any(Booking.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        BookingResponse response = bookingService.createBooking(request);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("CONFIRMED");
        // totalPrice = 5000.0 * 2 = 10000.0
        assertThat(response.getTotalPrice()).isEqualByComparingTo(new BigDecimal("10000.0"));
        assertThat(response.getUserId()).isEqualTo(userId);
        assertThat(response.getFlightId()).isEqualTo(flightId);
        assertThat(response.getPassengerName()).isEqualTo("John Doe");
        assertThat(response.getSeatCount()).isEqualTo(2);

        verify(flightServiceClient).getFlight(flightId.toString());
        verify(flightServiceClient).reserveSeats(eq(flightId.toString()), anyString(), eq(2));
        verify(bookingRepository).save(any(Booking.class));
    }

    @Test
    void createBooking_flightNotFound_throws404() {
        BookingRequest request = validRequest();
        when(flightServiceClient.getFlight(flightId.toString()))
            .thenThrow(new StatusRuntimeException(Status.NOT_FOUND.withDescription("Flight not found")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> bookingService.createBooking(request));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(flightServiceClient).getFlight(flightId.toString());
        verify(flightServiceClient, never()).reserveSeats(anyString(), anyString(), anyInt());
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void createBooking_noSeatsAvailable_throws409() {
        BookingRequest request = validRequest();
        when(flightServiceClient.getFlight(flightId.toString())).thenReturn(flightInfo);
        when(flightServiceClient.reserveSeats(anyString(), anyString(), anyInt()))
            .thenThrow(new StatusRuntimeException(
                Status.RESOURCE_EXHAUSTED.withDescription("Not enough seats")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> bookingService.createBooking(request));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void createBooking_flightNotScheduled_throws409() {
        BookingRequest request = validRequest();
        when(flightServiceClient.getFlight(flightId.toString())).thenReturn(flightInfo);
        when(flightServiceClient.reserveSeats(anyString(), anyString(), anyInt()))
            .thenThrow(new StatusRuntimeException(
                Status.FAILED_PRECONDITION.withDescription("Flight is cancelled")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> bookingService.createBooking(request));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void createBooking_missingUserId_throws400() {
        BookingRequest request = validRequest();
        request.setUserId(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> bookingService.createBooking(request));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(flightServiceClient, bookingRepository);
    }

    @Test
    void createBooking_missingFlightId_throws400() {
        BookingRequest request = validRequest();
        request.setFlightId(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> bookingService.createBooking(request));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(flightServiceClient, bookingRepository);
    }

    @Test
    void createBooking_blankPassengerName_throws400() {
        BookingRequest request = validRequest();
        request.setPassengerName("   ");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> bookingService.createBooking(request));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(flightServiceClient, bookingRepository);
    }

    @Test
    void createBooking_blankPassengerEmail_throws400() {
        BookingRequest request = validRequest();
        request.setPassengerEmail("");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> bookingService.createBooking(request));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(flightServiceClient, bookingRepository);
    }

    @Test
    void createBooking_seatCountZero_throws400() {
        BookingRequest request = validRequest();
        request.setSeatCount(0);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> bookingService.createBooking(request));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(flightServiceClient, bookingRepository);
    }

    // ─── getBooking ───────────────────────────────────────────────────────────

    @Test
    void getBooking_found_returnsResponse() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = savedBooking(bookingId, userId, flightId,
            "Jane Doe", "jane@example.com", 1,
            new BigDecimal("5000.00"), BookingStatus.CONFIRMED);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));

        BookingResponse response = bookingService.getBooking(bookingId);

        assertThat(response.getId()).isEqualTo(bookingId);
        assertThat(response.getStatus()).isEqualTo("CONFIRMED");
        assertThat(response.getPassengerName()).isEqualTo("Jane Doe");
    }

    @Test
    void getBooking_notFound_throws404() {
        UUID bookingId = UUID.randomUUID();
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> bookingService.getBooking(bookingId));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─── getBookingsByUser ────────────────────────────────────────────────────

    @Test
    void getBookingsByUser_returnsAll() {
        UUID b1 = UUID.randomUUID();
        UUID b2 = UUID.randomUUID();
        Booking booking1 = savedBooking(b1, userId, flightId,
            "Alice", "alice@example.com", 1,
            new BigDecimal("5000.00"), BookingStatus.CONFIRMED);
        Booking booking2 = savedBooking(b2, userId, flightId,
            "Bob", "bob@example.com", 2,
            new BigDecimal("10000.00"), BookingStatus.CANCELLED);
        when(bookingRepository.findByUserId(userId)).thenReturn(List.of(booking1, booking2));

        List<BookingResponse> responses = bookingService.getBookingsByUser(userId);

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).getId()).isEqualTo(b1);
        assertThat(responses.get(1).getId()).isEqualTo(b2);
    }

    // ─── cancelBooking ────────────────────────────────────────────────────────

    @Test
    void cancelBooking_success_setsStatusCancelled() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = savedBooking(bookingId, userId, flightId,
            "John", "john@example.com", 2,
            new BigDecimal("10000.00"), BookingStatus.CONFIRMED);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(flightServiceClient.releaseReservation(bookingId.toString()))
            .thenReturn(ReleaseReservationResponse.newBuilder().build());
        when(bookingRepository.save(any(Booking.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        BookingResponse response = bookingService.cancelBooking(bookingId);

        assertThat(response.getStatus()).isEqualTo("CANCELLED");
        verify(flightServiceClient).releaseReservation(bookingId.toString());
        verify(bookingRepository).save(booking);
    }

    @Test
    void cancelBooking_alreadyCancelled_throws409() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = savedBooking(bookingId, userId, flightId,
            "John", "john@example.com", 2,
            new BigDecimal("10000.00"), BookingStatus.CANCELLED);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> bookingService.cancelBooking(bookingId));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verifyNoInteractions(flightServiceClient);
    }

    @Test
    void cancelBooking_bookingNotFound_throws404() {
        UUID bookingId = UUID.randomUUID();
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> bookingService.cancelBooking(bookingId));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void cancelBooking_releaseReturnsNotFound_stillCancels() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = savedBooking(bookingId, userId, flightId,
            "John", "john@example.com", 2,
            new BigDecimal("10000.00"), BookingStatus.CONFIRMED);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(flightServiceClient.releaseReservation(bookingId.toString()))
            .thenThrow(new StatusRuntimeException(
                Status.NOT_FOUND.withDescription("Reservation not found")));
        when(bookingRepository.save(any(Booking.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        // Should NOT throw — NOT_FOUND during release is treated as a warning and booking is still cancelled
        BookingResponse response = bookingService.cancelBooking(bookingId);

        assertThat(response.getStatus()).isEqualTo("CANCELLED");
        verify(bookingRepository).save(booking);
    }
}
