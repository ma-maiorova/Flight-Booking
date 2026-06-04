package com.flightbooking.bookingservice.circuitbreaker;

import com.flightbooking.bookingservice.exception.ServiceUnavailableException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CircuitBreakerAspectTest {

    @Mock
    private CircuitBreaker circuitBreaker;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private MethodSignature methodSignature;

    @InjectMocks
    private CircuitBreakerAspect circuitBreakerAspect;

    @BeforeEach
    void setUp() {
        // All tests need the join point to report the method name
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getName()).thenReturn("getFlight");
    }

    // ─── Circuit OPEN ─────────────────────────────────────────────────────────

    @Test
    void whenCircuitOpen_throwsServiceUnavailableException() {
        when(circuitBreaker.isOpen()).thenReturn(true);

        assertThrows(ServiceUnavailableException.class,
            () -> circuitBreakerAspect.protect(joinPoint));

        verify(circuitBreaker, never()).recordSuccess();
        verify(circuitBreaker, never()).recordFailure();
    }

    // ─── Circuit CLOSED — success ─────────────────────────────────────────────

    @Test
    void whenCircuitClosed_success_recordsSuccess() throws Throwable {
        when(circuitBreaker.isOpen()).thenReturn(false);
        when(joinPoint.proceed()).thenReturn("result");

        Object result = circuitBreakerAspect.protect(joinPoint);

        assertThat(result).isEqualTo("result");
        verify(circuitBreaker).recordSuccess();
        verify(circuitBreaker, never()).recordFailure();
    }

    // ─── Circuit CLOSED — infrastructure failures ──────────────────────────────

    @Test
    void whenCircuitClosed_unavailableStatus_recordsFailure_rethrows() throws Throwable {
        when(circuitBreaker.isOpen()).thenReturn(false);
        StatusRuntimeException exception = new StatusRuntimeException(Status.UNAVAILABLE);
        when(joinPoint.proceed()).thenThrow(exception);

        StatusRuntimeException thrown = assertThrows(StatusRuntimeException.class,
            () -> circuitBreakerAspect.protect(joinPoint));

        assertThat(thrown).isSameAs(exception);
        verify(circuitBreaker).recordFailure();
        verify(circuitBreaker, never()).recordSuccess();
    }

    @Test
    void whenCircuitClosed_internalStatus_recordsFailure_rethrows() throws Throwable {
        when(circuitBreaker.isOpen()).thenReturn(false);
        StatusRuntimeException exception = new StatusRuntimeException(Status.INTERNAL);
        when(joinPoint.proceed()).thenThrow(exception);

        StatusRuntimeException thrown = assertThrows(StatusRuntimeException.class,
            () -> circuitBreakerAspect.protect(joinPoint));

        assertThat(thrown).isSameAs(exception);
        verify(circuitBreaker).recordFailure();
        verify(circuitBreaker, never()).recordSuccess();
    }

    // ─── Circuit CLOSED — business errors (record success, still rethrow) ──────

    @Test
    void whenCircuitClosed_notFoundStatus_recordsSuccess_rethrows() throws Throwable {
        when(circuitBreaker.isOpen()).thenReturn(false);
        StatusRuntimeException exception = new StatusRuntimeException(Status.NOT_FOUND);
        when(joinPoint.proceed()).thenThrow(exception);

        StatusRuntimeException thrown = assertThrows(StatusRuntimeException.class,
            () -> circuitBreakerAspect.protect(joinPoint));

        assertThat(thrown).isSameAs(exception);
        verify(circuitBreaker).recordSuccess();
        verify(circuitBreaker, never()).recordFailure();
    }

    @Test
    void whenCircuitClosed_resourceExhausted_recordsSuccess_rethrows() throws Throwable {
        when(circuitBreaker.isOpen()).thenReturn(false);
        StatusRuntimeException exception = new StatusRuntimeException(Status.RESOURCE_EXHAUSTED);
        when(joinPoint.proceed()).thenThrow(exception);

        StatusRuntimeException thrown = assertThrows(StatusRuntimeException.class,
            () -> circuitBreakerAspect.protect(joinPoint));

        assertThat(thrown).isSameAs(exception);
        verify(circuitBreaker).recordSuccess();
        verify(circuitBreaker, never()).recordFailure();
    }

    // ─── Circuit HALF_OPEN — success ──────────────────────────────────────────

    @Test
    void whenHalfOpen_success_recordsSuccess() throws Throwable {
        // isOpen() returns false when in HALF_OPEN (probe request allowed through)
        when(circuitBreaker.isOpen()).thenReturn(false);
        when(joinPoint.proceed()).thenReturn("probe-result");

        Object result = circuitBreakerAspect.protect(joinPoint);

        assertThat(result).isEqualTo("probe-result");
        verify(circuitBreaker).recordSuccess();
        verify(circuitBreaker, never()).recordFailure();
    }
}
