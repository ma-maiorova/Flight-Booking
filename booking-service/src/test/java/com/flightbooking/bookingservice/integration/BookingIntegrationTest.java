package com.flightbooking.bookingservice.integration;

import com.flightbooking.bookingservice.domain.Booking;
import com.flightbooking.bookingservice.domain.BookingStatus;
import com.flightbooking.bookingservice.dto.BookingRequest;
import com.flightbooking.bookingservice.dto.BookingResponse;
import com.flightbooking.bookingservice.repository.BookingRepository;
import com.flightbooking.grpc.*;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for booking-service — verifies the full request path:
 *   REST API → BookingService → FlightServiceClient (real gRPC call) → BookingRepository → PostgreSQL
 *
 * An in-process mock gRPC server simulates flight-service responses so the
 * test runs without a real flight-service instance.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BookingIntegrationTest {

    // ─── Infrastructure ────────────────────────────────────────────────────────

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("booking_db")
            .withUsername("booking_user")
            .withPassword("booking_pass");

    // Mock gRPC server started before Spring context via static initializer
    static Server mockFlightServer;
    static int mockGrpcPort;

    static {
        try {
            mockFlightServer = ServerBuilder.forPort(0)
                    .addService(new MockFlightServiceImpl())
                    .build()
                    .start();
            mockGrpcPort = mockFlightServer.getPort();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("grpc.client.flight-service.address",
                () -> "static://localhost:" + mockGrpcPort);
        registry.add("grpc.client.flight-service.negotiation-type", () -> "plaintext");
    }

    @AfterAll
    static void stopMockServer() throws Exception {
        if (mockFlightServer != null) mockFlightServer.shutdownNow();
    }

    // ─── Test fixtures ─────────────────────────────────────────────────────────

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    BookingRepository bookingRepository;

    static final UUID FLIGHT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final UUID USER_ID   = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @BeforeEach
    void cleanDatabase() {
        bookingRepository.deleteAll();
    }

    // ─── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void createBooking_callsFlightServiceViaGrpc_savesInDb() {
        BookingRequest req = buildRequest(FLIGHT_ID, USER_ID, "Alice", "alice@example.com", 2);

        ResponseEntity<BookingResponse> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/bookings", req, BookingResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        BookingResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo("CONFIRMED");
        assertThat(body.getSeatCount()).isEqualTo(2);
        assertThat(body.getTotalPrice().doubleValue()).isEqualTo(5990.0 * 2);

        // Verify booking persisted in real PostgreSQL
        Optional<Booking> saved = bookingRepository.findById(body.getId());
        assertThat(saved).isPresent();
        assertThat(saved.get().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(saved.get().getPassengerEmail()).isEqualTo("alice@example.com");
    }

    @Test
    @Order(2)
    void getBooking_afterCreate_returnsCorrectData() {
        BookingRequest req = buildRequest(FLIGHT_ID, USER_ID, "Bob", "bob@example.com", 1);
        ResponseEntity<BookingResponse> created = restTemplate.postForEntity(
                "http://localhost:" + port + "/bookings", req, BookingResponse.class);
        UUID bookingId = created.getBody().getId();

        ResponseEntity<BookingResponse> fetched = restTemplate.getForEntity(
                "http://localhost:" + port + "/bookings/" + bookingId, BookingResponse.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().getPassengerName()).isEqualTo("Bob");
        assertThat(fetched.getBody().getStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    @Order(3)
    void cancelBooking_callsReleaseReservationViaGrpc_updatesDbStatus() {
        BookingRequest req = buildRequest(FLIGHT_ID, USER_ID, "Carol", "carol@example.com", 1);
        ResponseEntity<BookingResponse> created = restTemplate.postForEntity(
                "http://localhost:" + port + "/bookings", req, BookingResponse.class);
        UUID bookingId = created.getBody().getId();

        ResponseEntity<BookingResponse> cancelled = restTemplate.postForEntity(
                "http://localhost:" + port + "/bookings/" + bookingId + "/cancel",
                null, BookingResponse.class);

        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().getStatus()).isEqualTo("CANCELLED");

        // Verify DB updated
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    @Order(4)
    void cancelBooking_alreadyCancelled_returns409() {
        BookingRequest req = buildRequest(FLIGHT_ID, USER_ID, "Dave", "dave@example.com", 1);
        ResponseEntity<BookingResponse> created = restTemplate.postForEntity(
                "http://localhost:" + port + "/bookings", req, BookingResponse.class);
        UUID bookingId = created.getBody().getId();

        restTemplate.postForEntity(
                "http://localhost:" + port + "/bookings/" + bookingId + "/cancel",
                null, BookingResponse.class);

        ResponseEntity<String> secondCancel = restTemplate.postForEntity(
                "http://localhost:" + port + "/bookings/" + bookingId + "/cancel",
                null, String.class);

        assertThat(secondCancel.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @Order(5)
    void getBooking_notFound_returns404() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/bookings/" + UUID.randomUUID(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private BookingRequest buildRequest(UUID flightId, UUID userId,
                                        String name, String email, int seats) {
        BookingRequest req = new BookingRequest();
        req.setFlightId(flightId);
        req.setUserId(userId);
        req.setPassengerName(name);
        req.setPassengerEmail(email);
        req.setSeatCount(seats);
        return req;
    }

    // ─── Mock flight-service gRPC implementation ───────────────────────────────

    static class MockFlightServiceImpl extends FlightServiceGrpc.FlightServiceImplBase {

        @Override
        public void getFlight(GetFlightRequest request,
                              StreamObserver<GetFlightResponse> responseObserver) {
            FlightInfo info = FlightInfo.newBuilder()
                    .setId(request.getFlightId())
                    .setFlightNumber("SU1234")
                    .setAirlineCode("SU")
                    .setAirlineName("Aeroflot")
                    .setOrigin("SVO")
                    .setDestination("LED")
                    .setTotalSeats(180)
                    .setAvailableSeats(45)
                    .setPrice(5990.0)
                    .setStatus(FlightStatus.SCHEDULED)
                    .build();
            responseObserver.onNext(GetFlightResponse.newBuilder().setFlight(info).build());
            responseObserver.onCompleted();
        }

        @Override
        public void reserveSeats(ReserveSeatsRequest request,
                                 StreamObserver<ReserveSeatsResponse> responseObserver) {
            responseObserver.onNext(ReserveSeatsResponse.newBuilder()
                    .setReservationId(UUID.randomUUID().toString())
                    .setBookingId(request.getBookingId())
                    .setFlightId(request.getFlightId())
                    .setReservedSeats(request.getSeatCount())
                    .setStatus(ReservationStatus.ACTIVE)
                    .build());
            responseObserver.onCompleted();
        }

        @Override
        public void releaseReservation(ReleaseReservationRequest request,
                                       StreamObserver<ReleaseReservationResponse> responseObserver) {
            responseObserver.onNext(ReleaseReservationResponse.newBuilder()
                    .setReservationId(UUID.randomUUID().toString())
                    .setBookingId(request.getBookingId())
                    .setReleasedSeats(1)
                    .setStatus(ReservationStatus.RELEASED)
                    .build());
            responseObserver.onCompleted();
        }

        @Override
        public void searchFlights(SearchFlightsRequest request,
                                  StreamObserver<SearchFlightsResponse> responseObserver) {
            responseObserver.onNext(SearchFlightsResponse.newBuilder().build());
            responseObserver.onCompleted();
        }
    }
}
