package com.sajtech.identity.infrastructure.runtime.grpc;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
      String bindAddress,
      int port,
      int maxConcurrentCallsPerConnection,
      boolean enabled,
      List<BindableService> services,
      ServerInterceptor tracingInterceptor,
      ServerInterceptor admissionInterceptor) {
    if (bindAddress == null
        || bindAddress.isBlank()
        || port <= 0
        || port > 65535
        || maxConcurrentCallsPerConnection <= 0) {
      throw new IllegalArgumentException("Identity gRPC configuration is invalid");
    }
    Objects.requireNonNull(services);
    Objects.requireNonNull(tracingInterceptor);
    Objects.requireNonNull(admissionInterceptor);
    if (!enabled) {
      server = null;
      executor = null;
      return;
    }
    if (services.isEmpty()) {
      throw new IllegalArgumentException("Enabled Identity gRPC runtime has no service");
    }
    executor = Executors.newVirtualThreadPerTaskExecutor();
    NettyServerBuilder builder =
        NettyServerBuilder.forAddress(new InetSocketAddress(bindAddress, port))
            .executor(executor)
            .maxConcurrentCallsPerConnection(maxConcurrentCallsPerConnection)
            .maxInboundMessageSize(MAX_INBOUND_MESSAGE_BYTES)
            .maxInboundMetadataSize(MAX_INBOUND_METADATA_BYTES);
    for (BindableService service : services) {
      ServerServiceDefinition tracedService =
          ServerInterceptors.intercept(Objects.requireNonNull(service), tracingInterceptor);
      ServerServiceDefinition admittedService =
          ServerInterceptors.intercept(tracedService, admissionInterceptor);
      builder.addService(admittedService);
    }
    server = builder.build();
  }

  @Override
  public synchronized void start() {
    if (server == null || running) return;
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
    if (server == null || !running) return;
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
