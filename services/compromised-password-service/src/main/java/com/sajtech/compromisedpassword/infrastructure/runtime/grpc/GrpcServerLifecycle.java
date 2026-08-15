package com.sajtech.compromisedpassword.infrastructure.runtime.grpc;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

public final class GrpcServerLifecycle implements SmartLifecycle {
  private static final Logger LOGGER = LoggerFactory.getLogger(GrpcServerLifecycle.class);
  private static final int MAX_INBOUND_MESSAGE_BYTES = 16 * 1024;
  private static final int MAX_INBOUND_METADATA_BYTES = 16 * 1024;

  private final Server server;
  private final ExecutorService requestExecutor;
  private volatile boolean running;

  public GrpcServerLifecycle(int port, BindableService service, ServerInterceptor interceptor) {
    if (port <= 0 || port > 65_535) {
      throw new IllegalArgumentException("Invalid gRPC port");
    }
    Objects.requireNonNull(service, "service");
    Objects.requireNonNull(interceptor, "interceptor");
    this.requestExecutor = Executors.newVirtualThreadPerTaskExecutor();
    this.server =
        ServerBuilder.forPort(port)
            .executor(requestExecutor)
            .maxInboundMessageSize(MAX_INBOUND_MESSAGE_BYTES)
            .maxInboundMetadataSize(MAX_INBOUND_METADATA_BYTES)
            .addService(ServerInterceptors.intercept(service, interceptor))
            .build();
  }

  @Override
  public synchronized void start() {
    if (running) {
      return;
    }
    try {
      server.start();
      running = true;
      LOGGER
          .atInfo()
          .addKeyValue("eventCode", "CP_GRPC_SERVER_STARTED")
          .log("Compromised password gRPC server started");
    } catch (IOException exception) {
      requestExecutor.close();
      throw new IllegalStateException("Unable to start gRPC server", exception);
    }
  }

  @Override
  public synchronized void stop() {
    if (!running) {
      return;
    }
    server.shutdown();
    try {
      if (!server.awaitTermination(10, TimeUnit.SECONDS)) {
        server.shutdownNow();
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      server.shutdownNow();
    } finally {
      requestExecutor.close();
      running = false;
      LOGGER
          .atInfo()
          .addKeyValue("eventCode", "CP_GRPC_SERVER_STOPPED")
          .log("Compromised password gRPC server stopped");
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
