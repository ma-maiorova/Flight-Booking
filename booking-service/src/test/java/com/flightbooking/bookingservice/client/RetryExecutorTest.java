package com.flightbooking.bookingservice.client;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class RetryExecutorTest {

    private RetryExecutor retryExecutor;

    @BeforeEach
    void setUp() {
        retryExecutor = new RetryExecutor();
        ReflectionTestUtils.setField(retryExecutor, "maxAttempts", 3);
        ReflectionTestUtils.setField(retryExecutor, "initialBackoffMs", 1L);
    }

    // ─── RETRYABLE: UNAVAILABLE ────────────────────────────────────────────────

    @Test
    void unavailable_retriesUpToMax_thenThrows() {
        StatusRuntimeException unavailable = new StatusRuntimeException(Status.UNAVAILABLE);

        @SuppressWarnings("unchecked")
        Supplier<String> supplier = mock(Supplier.class);
        when(supplier.get()).thenThrow(unavailable);

        StatusRuntimeException thrown = assertThrows(StatusRuntimeException.class,
            () -> retryExecutor.execute("test", supplier));

        assertThat(thrown.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        // 3 attempts total
        verify(supplier, times(3)).get();
    }

    @Test
    void unavailable_succeedsOnSecondAttempt_returns() {
        StatusRuntimeException unavailable = new StatusRuntimeException(Status.UNAVAILABLE);

        @SuppressWarnings("unchecked")
        Supplier<String> supplier = mock(Supplier.class);
        when(supplier.get())
            .thenThrow(unavailable)
            .thenReturn("success");

        String result = retryExecutor.execute("test", supplier);

        assertThat(result).isEqualTo("success");
        verify(supplier, times(2)).get();
    }

    // ─── RETRYABLE: DEADLINE_EXCEEDED ──────────────────────────────────────────

    @Test
    void deadlineExceeded_retriesUpToMax_thenThrows() {
        StatusRuntimeException deadline = new StatusRuntimeException(Status.DEADLINE_EXCEEDED);

        @SuppressWarnings("unchecked")
        Supplier<String> supplier = mock(Supplier.class);
        when(supplier.get()).thenThrow(deadline);

        StatusRuntimeException thrown = assertThrows(StatusRuntimeException.class,
            () -> retryExecutor.execute("test", supplier));

        assertThat(thrown.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED);
        verify(supplier, times(3)).get();
    }

    // ─── NON_RETRYABLE ─────────────────────────────────────────────────────────

    @Test
    void notFound_noRetry_throwsImmediately() {
        StatusRuntimeException notFound = new StatusRuntimeException(Status.NOT_FOUND);

        @SuppressWarnings("unchecked")
        Supplier<String> supplier = mock(Supplier.class);
        when(supplier.get()).thenThrow(notFound);

        assertThrows(StatusRuntimeException.class,
            () -> retryExecutor.execute("test", supplier));

        // Only called once — no retry
        verify(supplier, times(1)).get();
    }

    @Test
    void resourceExhausted_noRetry_throwsImmediately() {
        StatusRuntimeException exhausted = new StatusRuntimeException(Status.RESOURCE_EXHAUSTED);

        @SuppressWarnings("unchecked")
        Supplier<String> supplier = mock(Supplier.class);
        when(supplier.get()).thenThrow(exhausted);

        assertThrows(StatusRuntimeException.class,
            () -> retryExecutor.execute("test", supplier));

        verify(supplier, times(1)).get();
    }

    @Test
    void invalidArgument_noRetry_throwsImmediately() {
        StatusRuntimeException invalidArg = new StatusRuntimeException(Status.INVALID_ARGUMENT);

        @SuppressWarnings("unchecked")
        Supplier<String> supplier = mock(Supplier.class);
        when(supplier.get()).thenThrow(invalidArg);

        assertThrows(StatusRuntimeException.class,
            () -> retryExecutor.execute("test", supplier));

        verify(supplier, times(1)).get();
    }

    // ─── OTHER (not in RETRYABLE or NON_RETRYABLE sets) ──────────────────────

    @Test
    void internalError_notRetryable_noMoreAttempts_throws() {
        // INTERNAL is not in RETRYABLE — after incrementing attempt to 1
        // the check: !RETRYABLE.contains(code) || attempt >= maxAttempts → true (not retryable)
        // so it throws immediately after 1 attempt
        StatusRuntimeException internal = new StatusRuntimeException(Status.INTERNAL);

        @SuppressWarnings("unchecked")
        Supplier<String> supplier = mock(Supplier.class);
        when(supplier.get()).thenThrow(internal);

        StatusRuntimeException thrown = assertThrows(StatusRuntimeException.class,
            () -> retryExecutor.execute("test", supplier));

        assertThat(thrown.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        // INTERNAL is not retryable (not in RETRYABLE set), so no retries
        verify(supplier, times(1)).get();
    }

    // ─── SUCCESS ──────────────────────────────────────────────────────────────

    @Test
    void success_firstAttempt_returnsResult() {
        @SuppressWarnings("unchecked")
        Supplier<String> supplier = mock(Supplier.class);
        when(supplier.get()).thenReturn("ok");

        String result = retryExecutor.execute("test", supplier);

        assertThat(result).isEqualTo("ok");
        verify(supplier, times(1)).get();
    }
}
