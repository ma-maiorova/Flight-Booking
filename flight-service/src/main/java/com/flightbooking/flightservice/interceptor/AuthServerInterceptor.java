package com.flightbooking.flightservice.interceptor;

import io.grpc.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * gRPC server interceptor that validates the "x-api-key" metadata header.
 * Applied globally to all gRPC methods in FlightGrpcService.
 * Returns UNAUTHENTICATED if the key is missing or invalid.
 */
@Slf4j
@Component
public class AuthServerInterceptor implements ServerInterceptor {

    public static final Metadata.Key<String> API_KEY_HEADER =
        Metadata.Key.of("x-api-key", Metadata.ASCII_STRING_MARSHALLER);

    @Value("${flight-service.api-key}")
    private String expectedApiKey;

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call,
        Metadata headers,
        ServerCallHandler<ReqT, RespT> next
    ) {
        String receivedKey = headers.get(API_KEY_HEADER);

        if (receivedKey == null || receivedKey.isBlank()) {
            log.warn("gRPC call rejected: missing x-api-key header. method={}",
                call.getMethodDescriptor().getFullMethodName());
            call.close(
                Status.UNAUTHENTICATED.withDescription("Missing API key"),
                new Metadata()
            );
            return new ServerCall.Listener<>() {};
        }

        if (!expectedApiKey.equals(receivedKey)) {
            log.warn("gRPC call rejected: invalid x-api-key. method={}",
                call.getMethodDescriptor().getFullMethodName());
            call.close(
                Status.UNAUTHENTICATED.withDescription("Invalid API key"),
                new Metadata()
            );
            return new ServerCall.Listener<>() {};
        }

        return next.startCall(call, headers);
    }
}
