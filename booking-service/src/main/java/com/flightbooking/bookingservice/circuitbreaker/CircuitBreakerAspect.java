package com.flightbooking.bookingservice.circuitbreaker;

import com.flightbooking.bookingservice.exception.ServiceUnavailableException;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Spring AOP aspect that wraps all public methods of FlightServiceClient
 * with circuit breaker logic. Acts as the "interceptor/middleware" layer.
 *
 * When circuit is OPEN, calls fail fast with ServiceUnavailableException
 * (translated to HTTP 503) instead of timing out.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class CircuitBreakerAspect {

    private final CircuitBreaker circuitBreaker;

    @Around("execution(public * com.flightbooking.bookingservice.client.FlightServiceClient.*(..))")
    public Object protect(ProceedingJoinPoint joinPoint) throws Throwable {
        String method = joinPoint.getSignature().getName();

        if (circuitBreaker.isOpen()) {
            log.warn("[CircuitBreaker] OPEN — rejecting call to FlightServiceClient.{}", method);
            throw new ServiceUnavailableException(
                "Flight Service is currently unavailable (circuit breaker OPEN). " +
                "Please try again later.");
        }

        try {
            Object result = joinPoint.proceed();
            circuitBreaker.recordSuccess();
            return result;
        } catch (ServiceUnavailableException e) {
            // Already a CB exception from a nested call — don't double-count
            throw e;
        } catch (StatusRuntimeException e) {
            // Only count connectivity failures, not business errors
            switch (e.getStatus().getCode()) {
                case UNAVAILABLE, DEADLINE_EXCEEDED, INTERNAL -> {
                    circuitBreaker.recordFailure();
                    log.warn("[CircuitBreaker] Failure recorded for method={} status={}",
                        method, e.getStatus().getCode());
                }
                default -> {
                    // Business errors (NOT_FOUND, RESOURCE_EXHAUSTED, etc.) don't trip the CB
                    circuitBreaker.recordSuccess();
                }
            }
            throw e;
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.warn("[CircuitBreaker] Unexpected failure for method={}", method, e);
            throw e;
        }
    }
}
