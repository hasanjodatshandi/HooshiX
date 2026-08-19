package com.sajtech.identity.infrastructure.runtime.grpc;

import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

public final class GrpcServerLifecycle implements SmartLifecycle {
  private static final Logger LOGGER = LoggerFactory.getLogger(GrpcServerLifecycle.class);
  private static final int MAX_INBOUND_MESSAGE_BYTES = 64 * 1024;
  private static final int MAX_INBOUND_METADATA_BYTES = 16 * 1024;
  private final Server server;
  private final ExecutorService executor;
  private volatile boolean running;

  public GrpcServerLifecycle(
      int port,
      int maxConcurrentCallsPerConnection,
      BindableService service,
      ServerInterceptor interceptor) {
    if (port <= 0 || port > 65535 || maxConcurrentCallsPerConnection <= 0)
      throw new IllegalArgumentException("Identity gRPC configuration is invalid");
    Objects.requireNonNull(service);
    Objects.requireNonNull(interceptor);
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server =
        NettyServerBuilder.forPort(port)
            .executor(executor)
            .maxConcurrentCallsPerConnection(maxConcurrentCallsPerConnection)
            .maxInboundMessageSize(MAX_INBOUND_MESSAGE_BYTES)
            .maxInboundMetadataSize(MAX_INBOUND_METADATA_BYTES)
            .addService(ServerInterceptors.intercept(service, interceptor))
            .build();
  }

  @Override
  public synchronized void start() {
    if (running) return;
    try {
      server.start();
      running = true;
      LOGGER
          .atInfo()
          .addKeyValue("eventCode", "IDENTITY_GRPC_SERVER_STARTED")
          .log("Identity gRPC server started");
    } catch (IOException exception) {
      executor.shutdownNow();
      throw new IllegalStateException("Unable to start Identity gRPC server", exception);
    }
  }

  @Override
  public synchronized void stop() {
    if (!running) return;
    server.shutdown();
    boolean interrupted = false;
    try {
      if (!server.awaitTermination(10, TimeUnit.SECONDS)) server.shutdownNow();
    } catch (InterruptedException exception) {
      interrupted = true;
      server.shutdownNow();
    } finally {
      executor.shutdownNow();
      running = false;
      LOGGER
          .atInfo()
          .addKeyValue("eventCode", "IDENTITY_GRPC_SERVER_STOPPED")
          .log("Identity gRPC server stopped");
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
