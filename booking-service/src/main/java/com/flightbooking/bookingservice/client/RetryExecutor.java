package com.flightbooking.bookingservice.client;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Retry executor for gRPC calls to Flight Service.
 *
 * Retryable status codes:  UNAVAILABLE, DEADLINE_EXCEEDED
 * Non-retryable:           INVALID_ARGUMENT, NOT_FOUND, RESOURCE_EXHAUSTED
 *
 * Backoff: initialBackoffMs * 2^attempt  (100ms → 200ms → 400ms by default)
 */
@Slf4j
@Component
public class RetryExecutor {

    private static final Set<Status.Code> RETRYABLE = Set.of(
        Status.Code.UNAVAILABLE,
        Status.Code.DEADLINE_EXCEEDED
    );

    private static final Set<Status.Code> NON_RETRYABLE = Set.of(
        Status.Code.INVALID_ARGUMENT,
        Status.Code.NOT_FOUND,
        Status.Code.RESOURCE_EXHAUSTED,
        Status.Code.UNAUTHENTICATED,
        Status.Code.ALREADY_EXISTS
    );

    @Value("${booking-service.retry.max-attempts:3}")
    private int maxAttempts;

    @Value("${booking-service.retry.initial-backoff-ms:100}")
    private long initialBackoffMs;

    /**
     * Execute the supplied gRPC call with retry logic.
     *
     * @param operationName human-readable name for logging
     * @param operation     the gRPC call to execute
     * @param <T>           return type
     * @return result of the operation
     */
    public <T> T execute(String operationName, Supplier<T> operation) {
        int attempt = 0;
        while (true) {
            try {
                T result = operation.get();
                if (attempt > 0) {
                    log.info("[Retry] {} succeeded on attempt {}", operationName, attempt + 1);
                }
                return result;
            } catch (StatusRuntimeException e) {
                Status.Code code = e.getStatus().getCode();

                if (NON_RETRYABLE.contains(code)) {
                    log.debug("[Retry] {} failed with non-retryable status {}, not retrying",
                        operationName, code);
                    throw e;
                }

                attempt++;
                if (!RETRYABLE.contains(code) || attempt >= maxAttempts) {
                    log.warn("[Retry] {} failed with status {} after {} attempt(s), giving up",
                        operationName, code, attempt);
                    throw e;
                }

                long backoff = initialBackoffMs * (1L << (attempt - 1)); // 2^(attempt-1)
                log.warn("[Retry] {} failed with status {} (attempt {}/{}), retrying in {}ms",
                    operationName, code, attempt, maxAttempts, backoff);

                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry backoff", ie);
                }
            }
        }
    }
}
