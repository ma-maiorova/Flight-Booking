package com.flightbooking.bookingservice.config;

import io.grpc.*;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Registers a global gRPC client interceptor that injects the API key
 * into outgoing metadata for all calls to Flight Service.
 */
@Slf4j
@Configuration
public class GrpcClientConfig {

    public static final Metadata.Key<String> API_KEY_HEADER =
        Metadata.Key.of("x-api-key", Metadata.ASCII_STRING_MARSHALLER);

    @Value("${booking-service.flight-service.api-key}")
    private String apiKey;

    @GrpcGlobalClientInterceptor
    public ClientInterceptor apiKeyInterceptor() {
        return new ClientInterceptor() {
            @Override
            public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                MethodDescriptor<ReqT, RespT> method,
                CallOptions callOptions,
                Channel next
            ) {
                return new ForwardingClientCall.SimpleForwardingClientCall<>(
                    next.newCall(method, callOptions)
                ) {
                    @Override
                    public void start(Listener<RespT> responseListener, Metadata headers) {
                        headers.put(API_KEY_HEADER, apiKey);
                        super.start(responseListener, headers);
                    }
                };
            }
        };
    }
}
