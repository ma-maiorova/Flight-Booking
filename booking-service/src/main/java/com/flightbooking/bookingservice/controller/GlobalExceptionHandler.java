package com.flightbooking.bookingservice.controller;

import com.flightbooking.bookingservice.exception.ServiceUnavailableException;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatus(ResponseStatusException ex) {
        return ProblemDetail.forStatusAndDetail(ex.getStatusCode(), ex.getReason());
    }

    /**
     * Circuit breaker OPEN — return 503 Service Unavailable immediately.
     */
    @ExceptionHandler(ServiceUnavailableException.class)
    public ProblemDetail handleServiceUnavailable(ServiceUnavailableException ex) {
        log.warn("Service unavailable: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    /**
     * Unhandled gRPC errors from Flight Service — map to appropriate HTTP status.
     */
    @ExceptionHandler(StatusRuntimeException.class)
    public ProblemDetail handleGrpcStatus(StatusRuntimeException ex) {
        log.error("Unhandled gRPC error: code={} desc={}",
            ex.getStatus().getCode(), ex.getStatus().getDescription());

        HttpStatus httpStatus = switch (ex.getStatus().getCode()) {
            case NOT_FOUND           -> HttpStatus.NOT_FOUND;
            case INVALID_ARGUMENT    -> HttpStatus.BAD_REQUEST;
            case UNAUTHENTICATED     -> HttpStatus.UNAUTHORIZED;
            case PERMISSION_DENIED   -> HttpStatus.FORBIDDEN;
            case ALREADY_EXISTS      -> HttpStatus.CONFLICT;
            case RESOURCE_EXHAUSTED  -> HttpStatus.CONFLICT;
            case FAILED_PRECONDITION -> HttpStatus.CONFLICT;
            case UNAVAILABLE         -> HttpStatus.SERVICE_UNAVAILABLE;
            default                  -> HttpStatus.INTERNAL_SERVER_ERROR;
        };

        String detail = ex.getStatus().getDescription() != null
            ? ex.getStatus().getDescription()
            : ex.getStatus().getCode().name();

        return ProblemDetail.forStatusAndDetail(httpStatus, detail);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }
}
