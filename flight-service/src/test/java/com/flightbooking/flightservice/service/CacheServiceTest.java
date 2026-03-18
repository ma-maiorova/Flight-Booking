package com.flightbooking.flightservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flightbooking.flightservice.domain.Airline;
import com.flightbooking.flightservice.domain.Flight;
import com.flightbooking.flightservice.domain.FlightStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CacheServiceTest {

    @Mock
    StringRedisTemplate redisTemplate;

    @Mock
    ObjectMapper objectMapper;

    @Mock
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> valueOps;

    @InjectMocks
    CacheService cacheService;

    private UUID flightId;
    private Flight flight;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(cacheService, "ttlMinutes", 10L);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        flightId = UUID.randomUUID();

        Airline airline = new Airline();
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

    // ─── getFlight ─────────────────────────────────────────────────────────────

    @Test
    void getFlight_cacheHit_returnsDeserializedFlight() throws Exception {
        String key = "flight:" + flightId;
        String json = "{\"id\":\"" + flightId + "\"}";
        when(valueOps.get(key)).thenReturn(json);
        when(objectMapper.readValue(json, Flight.class)).thenReturn(flight);

        Optional<Flight> result = cacheService.getFlight(flightId);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(flight);
    }

    @Test
    void getFlight_cacheMiss_returnsEmpty() {
        String key = "flight:" + flightId;
        when(valueOps.get(key)).thenReturn(null);

        Optional<Flight> result = cacheService.getFlight(flightId);

        assertThat(result).isEmpty();
    }

    @Test
    void getFlight_deserializationFails_deletesKeyAndReturnsEmpty() throws Exception {
        String key = "flight:" + flightId;
        String json = "invalid-json";
        when(valueOps.get(key)).thenReturn(json);
        when(objectMapper.readValue(json, Flight.class)).thenThrow(new RuntimeException("parse error"));

        Optional<Flight> result = cacheService.getFlight(flightId);

        assertThat(result).isEmpty();
        verify(redisTemplate).delete(key);
    }

    // ─── putFlight ─────────────────────────────────────────────────────────────

    @Test
    void putFlight_serializesAndStoresWithTtl() throws Exception {
        String key = "flight:" + flightId;
        String json = "{\"id\":\"" + flightId + "\"}";
        when(objectMapper.writeValueAsString(flight)).thenReturn(json);

        cacheService.putFlight(flight);

        verify(valueOps).set(key, json, 10L, TimeUnit.MINUTES);
    }

    // ─── evictFlight ───────────────────────────────────────────────────────────

    @Test
    void evictFlight_deletesKey() {
        String key = "flight:" + flightId;
        when(redisTemplate.delete(key)).thenReturn(true);

        cacheService.evictFlight(flightId);

        verify(redisTemplate).delete(key);
    }

    // ─── getSearch ─────────────────────────────────────────────────────────────

    @Test
    void getSearch_cacheHit_returnsList() throws Exception {
        String key = "search:SVO:LED:2026-04-01";
        String json = "[{\"id\":\"" + flightId + "\"}]";
        List<Flight> expected = List.of(flight);
        when(valueOps.get(key)).thenReturn(json);
        when(objectMapper.readValue(eq(json), any(TypeReference.class))).thenReturn(expected);

        Optional<List<Flight>> result = cacheService.getSearch("SVO", "LED", "2026-04-01");

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(expected);
    }

    @Test
    void getSearch_cacheMiss_returnsEmpty() {
        String key = "search:SVO:LED:all";
        when(valueOps.get(key)).thenReturn(null);

        Optional<List<Flight>> result = cacheService.getSearch("SVO", "LED", null);

        assertThat(result).isEmpty();
    }

    @Test
    void getSearch_withBlankDate_usesAllSuffix() {
        String key = "search:SVO:LED:all";
        when(valueOps.get(key)).thenReturn(null);

        Optional<List<Flight>> result = cacheService.getSearch("SVO", "LED", "");

        assertThat(result).isEmpty();
        verify(valueOps).get(key);
    }

    // ─── putSearch ─────────────────────────────────────────────────────────────

    @Test
    void putSearch_serializesAndStoresWithTtl() throws Exception {
        String key = "search:SVO:LED:2026-04-01";
        List<Flight> flights = List.of(flight);
        String json = "[{\"id\":\"" + flightId + "\"}]";
        when(objectMapper.writeValueAsString(flights)).thenReturn(json);

        cacheService.putSearch("SVO", "LED", "2026-04-01", flights);

        verify(valueOps).set(key, json, 10L, TimeUnit.MINUTES);
    }

    // ─── evictSearchByRoute ────────────────────────────────────────────────────

    @Test
    void evictSearchByRoute_deletesMatchingKeys() {
        String pattern = "search:SVO:LED:*";
        Set<String> keys = Set.of("search:SVO:LED:2026-04-01", "search:SVO:LED:all");
        when(redisTemplate.keys(pattern)).thenReturn(keys);

        cacheService.evictSearchByRoute("SVO", "LED");

        verify(redisTemplate).delete(keys);
    }

    @Test
    void evictSearchByRoute_noMatchingKeys_doesNotDelete() {
        String pattern = "search:SVO:LED:*";
        when(redisTemplate.keys(pattern)).thenReturn(Set.of());

        cacheService.evictSearchByRoute("SVO", "LED");

        verify(redisTemplate, never()).delete(anyCollection());
    }
}
