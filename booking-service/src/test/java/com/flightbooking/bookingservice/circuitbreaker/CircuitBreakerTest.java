package com.flightbooking.bookingservice.circuitbreaker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {

    private CircuitBreaker circuitBreaker;

    // failureThreshold=3, timeoutSeconds=1, halfOpenMaxCalls=2
    @BeforeEach
    void setUp() {
        circuitBreaker = new CircuitBreaker();
        ReflectionTestUtils.setField(circuitBreaker, "failureThreshold", 3);
        ReflectionTestUtils.setField(circuitBreaker, "timeoutSeconds", 1);
        ReflectionTestUtils.setField(circuitBreaker, "halfOpenMaxCalls", 2);
    }

    @Test
    void initialState_isClosed() {
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.isOpen()).isFalse();
    }

    @Test
    void isOpen_inClosed_returnsFalse() {
        assertThat(circuitBreaker.isOpen()).isFalse();
    }

    @Test
    void afterThresholdFailures_transitionsToOpen() {
        // Fail 3 times (threshold = 3)
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        circuitBreaker.recordFailure(); // 3rd failure hits threshold
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void inOpen_beforeTimeout_isOpen_returnsTrue() {
        // Trip the circuit breaker
        tripToOpen();

        // Immediately check — timeout (1s) has not elapsed yet
        assertThat(circuitBreaker.isOpen()).isTrue();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void inOpen_afterTimeout_transitionsToHalfOpen() throws InterruptedException {
        tripToOpen();

        // Wait for timeout to elapse (timeoutSeconds=1)
        Thread.sleep(1100);

        // isOpen() should transition OPEN → HALF_OPEN and return false (probe through)
        boolean result = circuitBreaker.isOpen();
        assertThat(result).isFalse();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void isOpen_inHalfOpen_returnsFalse() throws InterruptedException {
        tripToOpen();
        Thread.sleep(1100);
        circuitBreaker.isOpen(); // triggers OPEN → HALF_OPEN
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // In HALF_OPEN, isOpen() should return false
        assertThat(circuitBreaker.isOpen()).isFalse();
    }

    @Test
    void inHalfOpen_afterEnoughSuccesses_transitionsToClosed() throws InterruptedException {
        tripToOpen();
        Thread.sleep(1100);
        circuitBreaker.isOpen(); // OPEN → HALF_OPEN
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        circuitBreaker.recordSuccess(); // 1st success
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        circuitBreaker.recordSuccess(); // 2nd success — hits halfOpenMaxCalls=2
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void inHalfOpen_onFailure_transitionsBackToOpen() throws InterruptedException {
        tripToOpen();
        Thread.sleep(1100);
        circuitBreaker.isOpen(); // OPEN → HALF_OPEN

        circuitBreaker.recordFailure(); // probe fails → back to OPEN
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void inClosed_successResetsFailureCount() {
        // Record 2 failures (just below threshold of 3)
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // A success resets the failure count
        circuitBreaker.recordSuccess();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // Now need another 3 failures to open (not just 1)
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        circuitBreaker.recordFailure(); // 3rd failure after reset
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private void tripToOpen() {
        for (int i = 0; i < 3; i++) {
            circuitBreaker.recordFailure();
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
