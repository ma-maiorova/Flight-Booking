package com.flightbooking.flightservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flightbooking.flightservice.domain.Flight;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class CacheService {

    private static final String FLIGHT_KEY_PREFIX = "flight:";
    private static final String SEARCH_KEY_PREFIX = "search:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${flight-service.cache.ttl-minutes:10}")
    private long ttlMinutes;

    // ─── Flight cache ─────────────────────────────────────────────────────────

    public Optional<Flight> getFlight(UUID flightId) {
        String key = FLIGHT_KEY_PREFIX + flightId;
        String json = redisTemplate.opsForValue().get(key);
        if (json != null) {
            log.info("[CACHE HIT] key={}", key);
            try {
                return Optional.of(objectMapper.readValue(json, Flight.class));
            } catch (Exception e) {
                log.warn("[CACHE] Deserialization failed for key={}, evicting", key, e);
                redisTemplate.delete(key);
            }
        } else {
            log.info("[CACHE MISS] key={}", key);
        }
        return Optional.empty();
    }

    public void putFlight(Flight flight) {
        String key = FLIGHT_KEY_PREFIX + flight.getId();
        try {
            String json = objectMapper.writeValueAsString(flight);
            redisTemplate.opsForValue().set(key, json, ttlMinutes, TimeUnit.MINUTES);
            log.debug("[CACHE PUT] key={} ttl={}min", key, ttlMinutes);
        } catch (Exception e) {
            log.warn("[CACHE] Serialization failed for key={}", key, e);
        }
    }

    public void evictFlight(UUID flightId) {
        String key = FLIGHT_KEY_PREFIX + flightId;
        Boolean deleted = redisTemplate.delete(key);
        log.info("[CACHE EVICT] key={} deleted={}", key, deleted);
    }

    // ─── Search cache ─────────────────────────────────────────────────────────

    public Optional<List<Flight>> getSearch(String origin, String destination, String date) {
        String key = buildSearchKey(origin, destination, date);
        String json = redisTemplate.opsForValue().get(key);
        if (json != null) {
            log.info("[CACHE HIT] key={}", key);
            try {
                List<Flight> flights = objectMapper.readValue(json, new TypeReference<>() {});
                return Optional.of(flights);
            } catch (Exception e) {
                log.warn("[CACHE] Deserialization failed for key={}, evicting", key, e);
                redisTemplate.delete(key);
            }
        } else {
            log.info("[CACHE MISS] key={}", key);
        }
        return Optional.empty();
    }

    public void putSearch(String origin, String destination, String date, List<Flight> flights) {
        String key = buildSearchKey(origin, destination, date);
        try {
            String json = objectMapper.writeValueAsString(flights);
            redisTemplate.opsForValue().set(key, json, ttlMinutes, TimeUnit.MINUTES);
            log.debug("[CACHE PUT] key={} ttl={}min count={}", key, ttlMinutes, flights.size());
        } catch (Exception e) {
            log.warn("[CACHE] Serialization failed for key={}", key, e);
        }
    }

    public void evictSearchByRoute(String origin, String destination) {
        // Use SCAN to non-blockingly find and delete all keys for this route
        String pattern = SEARCH_KEY_PREFIX + origin + ":" + destination + ":*";
        List<String> keys = new ArrayList<>();
        redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            org.springframework.data.redis.core.Cursor<byte[]> cursor = connection.scan(
                    org.springframework.data.redis.core.ScanOptions.scanOptions().match(pattern).count(100).build());
            cursor.forEachRemaining(key -> keys.add(new String(key)));
            return null;
        });
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.info("[CACHE EVICT] pattern={} count={}", pattern, keys.size());
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String buildSearchKey(String origin, String destination, String date) {
        return SEARCH_KEY_PREFIX + origin + ":" + destination + ":" +
                (date != null && !date.isBlank() ? date : "all");
    }
}
