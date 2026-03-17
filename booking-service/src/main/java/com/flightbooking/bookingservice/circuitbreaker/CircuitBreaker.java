package com.flightbooking.bookingservice.circuitbreaker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe Circuit Breaker state machine with three states: CLOSED, OPEN, HALF_OPEN.
 *
 * <pre>
 * CLOSED ──(failures >= threshold)──► OPEN
 * OPEN   ──(timeout elapsed)────────► HALF_OPEN
 * HALF_OPEN ──(success)─────────────► CLOSED
 * HALF_OPEN ──(failure)─────────────► OPEN
 * </pre>
 *
 * All state transitions are logged.
 */
@Slf4j
@Component
public class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger halfOpenSuccessCount = new AtomicInteger(0);
    private volatile Instant openedAt;

    @Value("${booking-service.circuit-breaker.failure-threshold:5}")
    private int failureThreshold;

    @Value("${booking-service.circuit-breaker.timeout-seconds:30}")
    private int timeoutSeconds;

    @Value("${booking-service.circuit-breaker.half-open-max-calls:3}")
    private int halfOpenMaxCalls;

    /**
     * Returns true if the circuit is OPEN and the call should be rejected immediately.
     * Handles OPEN → HALF_OPEN transition.
     */
    public boolean isOpen() {
        State current = state.get();

        if (current == State.OPEN) {
            if (Duration.between(openedAt, Instant.now()).toSeconds() >= timeoutSeconds) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    halfOpenSuccessCount.set(0);
                    log.warn("[CircuitBreaker] OPEN → HALF_OPEN (timeout elapsed after {}s)", timeoutSeconds);
                }
                return false; // Let the probe request through
            }
            return true; // Still OPEN
        }

        return false;
    }

    /**
     * Record a successful call. Handles HALF_OPEN → CLOSED transition.
     */
    public void recordSuccess() {
        State current = state.get();

        if (current == State.HALF_OPEN) {
            int successes = halfOpenSuccessCount.incrementAndGet();
            if (successes >= halfOpenMaxCalls) {
                if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                    failureCount.set(0);
                    log.info("[CircuitBreaker] HALF_OPEN → CLOSED (after {} successful probe calls)", successes);
                }
            }
        } else if (current == State.CLOSED) {
            // Reset failure count on success in CLOSED state
            failureCount.set(0);
        }
    }

    /**
     * Record a failed call. Handles CLOSED → OPEN and HALF_OPEN → OPEN transitions.
     */
    public void recordFailure() {
        State current = state.get();

        if (current == State.HALF_OPEN) {
            if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                openedAt = Instant.now();
                log.warn("[CircuitBreaker] HALF_OPEN → OPEN (probe request failed)");
            }
            return;
        }

        if (current == State.CLOSED) {
            int failures = failureCount.incrementAndGet();
            log.debug("[CircuitBreaker] Failure recorded: {}/{}", failures, failureThreshold);
            if (failures >= failureThreshold) {
                if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                    openedAt = Instant.now();
                    log.warn("[CircuitBreaker] CLOSED → OPEN (failures={} threshold={})",
                        failures, failureThreshold);
                }
            }
        }
    }

    public State getState() {
        return state.get();
    }
}
