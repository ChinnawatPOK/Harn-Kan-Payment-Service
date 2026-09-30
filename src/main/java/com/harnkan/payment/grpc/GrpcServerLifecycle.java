package com.harnkan.payment.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class GrpcServerLifecycle implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);
    private final PaymentGrpcService paymentService;
    private final int port;
    private Server server;

    public GrpcServerLifecycle(PaymentGrpcService paymentService, @Value("${grpc.server.port:9091}") int port) {
        this.paymentService = paymentService;
        this.port = port;
    }

    @Override
    public synchronized void start() {
        if (isRunning()) return;
        try {
            server = ServerBuilder.forPort(port).addService(paymentService)
                    .addService(ProtoReflectionServiceV1.newInstance()).build().start();
            log.info("Payment gRPC server listening on {}", server.getPort());
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to start Payment gRPC server on port " + port, exception);
        }
    }

    @Override
    public synchronized void stop() {
        if (server == null) return;
        server.shutdown();
        try {
            if (!server.awaitTermination(10, TimeUnit.SECONDS)) server.shutdownNow();
        } catch (InterruptedException exception) {
            server.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return server != null && !server.isShutdown();
    }

    public synchronized int getPort() {
        return server == null ? port : server.getPort();
    }
}
