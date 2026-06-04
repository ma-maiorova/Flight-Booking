package com.flightbooking.flightservice.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class MetricsFilter extends OncePerRequestFilter {

    private final MeterRegistry meterRegistry;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator") || path.startsWith("/metrics");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long startNanos = System.nanoTime();
        String method = request.getMethod();
        String endpoint = normalizeEndpoint(request.getRequestURI());

        try {
            chain.doFilter(request, response);
        } finally {
            long durationNanos = System.nanoTime() - startNanos;
            String status = String.valueOf(response.getStatus());

            Counter.builder("http_requests_total")
                    .tag("method", method)
                    .tag("endpoint", endpoint)
                    .tag("status", status)
                    .register(meterRegistry)
                    .increment();

            if (response.getStatus() >= 400) {
                String errorType = response.getStatus() >= 500 ? "server_error" : "client_error";
                Counter.builder("http_request_errors_total")
                        .tag("method", method)
                        .tag("endpoint", endpoint)
                        .tag("error_type", errorType)
                        .register(meterRegistry)
                        .increment();
            }

            Timer.builder("http_request_duration_seconds")
                    .tag("method", method)
                    .tag("endpoint", endpoint)
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meterRegistry)
                    .record(durationNanos, TimeUnit.NANOSECONDS);
        }
    }

    private String normalizeEndpoint(String uri) {
        return uri.replaceAll(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                "{id}");
    }
}
